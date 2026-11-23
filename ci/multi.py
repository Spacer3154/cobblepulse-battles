#!/usr/bin/env python3
"""CI multi-serveur : deux vrais serveurs Fabric (lobby + combat) relies par Redis et une base MySQL commune.

File d'attente sur le lobby, matchmaking et combat sur le serveur de combat (equipe transmise par Redis), transfert
simule (joueurs de test), profil relu par le lobby, webhook Discord recu par un petit serveur HTTP local.
"""
import glob, http.server, json, os, queue, re, shutil, subprocess, sys, threading, time, urllib.request, zipfile

OUT = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else "out-multi")
DISC = os.path.abspath(sys.argv[2] if len(sys.argv) > 2 else "out/discover")
JARS = os.path.abspath(sys.argv[3] if len(sys.argv) > 3 else "out")
os.makedirs(OUT, exist_ok=True)
UA = {"User-Agent": "cobblepulse-battles-ci/2.0 (github actions)"}
props = dict(l.strip().split("=", 1) for l in open("gradle.properties") if "=" in l and not l.startswith("#"))


def get(url):
    req = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req, timeout=300) as r:
        return r.read()


def log(msg):
    print(msg, flush=True)
    with open(os.path.join(OUT, "multi-steps.txt"), "a") as fh:
        fh.write(msg + "\n")


# ------------------------------------------------------------------ webhook Discord factice
HOOKS = []


class Hook(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        n = int(self.headers.get("Content-Length", "0"))
        HOOKS.append(self.rfile.read(n).decode("utf-8", "replace"))
        self.send_response(204)
        self.end_headers()

    def log_message(self, *a):
        pass


hook_srv = http.server.HTTPServer(("127.0.0.1", 8765), Hook)
threading.Thread(target=hook_srv.serve_forever, daemon=True).start()

# ------------------------------------------------------------------ mods
MODS = os.path.abspath("multi-mods")
os.makedirs(MODS, exist_ok=True)
modrinth = json.load(open(os.path.join(DISC, "modrinth.json")))
wanted = {"fabric-api": None, "fabric-language-kotlin": None, "luckperms": None, "placeholder-api": None, "cobblemon": "1.8.4",
          "cobblemon-mega-showdown": "1.8.4"}
for dep in json.load(open(os.path.join(DISC, "msdeps.json"))):
    wanted.setdefault(dep, None)
for slug, ver in wanted.items():
    pick = None
    for v in modrinth.get(slug) or []:
        if ver is None or ver in v["version_number"]:
            pick = v
            break
    if not pick:
        log(f"!! missing {slug}")
        continue
    f = [f for f in pick["files"] if f["primary"]] or pick["files"]
    open(os.path.join(MODS, f[0]["filename"]), "wb").write(get(f[0]["url"]))
    log(f"mod {slug} {pick['version_number']}")
jar = [j for j in glob.glob(os.path.join(JARS, "cobblepulse-battles-*.jar")) if not j.endswith("-sources.jar")][0]
shutil.copy(jar, MODS)
log("mod battles " + os.path.basename(jar))
default_net = zipfile.ZipFile(jar).read("battletower_defaults/ranked/reseau.toml").decode("utf-8")

installer = json.loads(get("https://meta.fabricmc.net/v2/versions/installer"))[0]["version"]
server_jar = get(f"https://meta.fabricmc.net/v2/versions/loader/1.21.1/{props.get('loader_version')}/{installer}/server/jar")


def setup(name, port):
    d = os.path.abspath("multi-" + name)
    shutil.copytree(MODS, os.path.join(d, "mods"), dirs_exist_ok=True)
    open(os.path.join(d, "server.jar"), "wb").write(server_jar)
    open(os.path.join(d, "eula.txt"), "w").write("eula=true\n")
    open(os.path.join(d, "server.properties"), "w").write(
        "online-mode=false\nview-distance=4\nsimulation-distance=4\nspawn-protection=0\nmax-tick-time=-1\nlevel-seed=battles\n"
        f"server-port={port}\nsync-chunk-writes=false\n")
    cfg = os.path.join(d, "config", "cobblepulse_battles")
    os.makedirs(os.path.join(cfg, "ranked"), exist_ok=True)
    open(os.path.join(cfg, "storage.toml"), "w").write(
        'mode = "mysql"\n[mysql]\nhost = "127.0.0.1"\nport = 3306\ndatabase = "cobblepulse"\nusername = "root"\npassword = "battletower"\n'
        'table_prefix = "cp_multi_"\n')
    net = default_net
    net = re.sub(r'(?m)^actif = false', 'actif = true', net, count=1)
    net = re.sub(r'(?m)^id_serveur = ".*"', f'id_serveur = "{name}"', net)
    net = re.sub(r'(?m)^delai_transfert = \d+', 'delai_transfert = 1', net)
    if name == "combat":
        net = re.sub(r'(?ms)^\[discord\]\nactif = false\nwebhook = ""', '[discord]\nactif = true\nwebhook = "http://127.0.0.1:8765/webhook"', net)
    open(os.path.join(cfg, "ranked", "reseau.toml"), "w").write(net)
    return d


class Server:
    def __init__(self, name, port):
        self.name = name
        self.dir = setup(name, port)
        self.lines = queue.Queue()
        self.logf = open(os.path.join(OUT, f"server-{name}.log"), "w")
        self.proc = subprocess.Popen(["java", "-Xmx4G", "-Dbattletower.selftest=true", "-jar", "server.jar", "nogui"], cwd=self.dir,
                                     stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)
        threading.Thread(target=self.read, daemon=True).start()

    def read(self):
        for line in self.proc.stdout:
            self.logf.write(line)
            self.logf.flush()
            self.lines.put(line)
        self.lines.put(None)

    def wait(self, token, timeout):
        end = time.time() + timeout
        while time.time() < end:
            try:
                line = self.lines.get(timeout=1)
            except queue.Empty:
                continue
            if line is None:
                return False
            if token in line:
                return True
        return False

    def cmd(self, c):
        log(f"[{self.name}] > {c}")
        self.proc.stdin.write(c + "\n")
        self.proc.stdin.flush()

    def stop(self):
        try:
            self.cmd("stop")
        except Exception:
            pass
        try:
            self.proc.wait(timeout=180)
        except subprocess.TimeoutExpired:
            self.proc.kill()
        self.logf.close()
        for l in open(os.path.join(OUT, f"server-{self.name}.log"), errors="replace"):
            if "SELFTEST SUMMARY" in l or "FAIL " in l or "Exception" in l and "battles" in l.lower():
                log(f"[{self.name}] {l.strip()}")
        log(f"[{self.name}] exit code {self.proc.returncode}")


combat = Server("combat", 25566)
time.sleep(20)
lobby = Server("lobby", 25565)
ok_c = combat.wait("Done (", 1200)
ok_l = lobby.wait("Done (", 1200)
log(f"serveurs prets : combat={ok_c} lobby={ok_l}")
if ok_c and ok_l:
    time.sleep(10)
    lobby.cmd("battletower selftest multi lobby")
    log("multi-lobby : %s" % lobby.wait("SELFTEST-DONE multi-lobby", 90))
    combat.cmd("battletower selftest multi combat")
    log("multi-combat : %s" % combat.wait("SELFTEST-DONE multi-combat", 240))
    time.sleep(3)
    lobby.cmd("battletower selftest multi check")
    log("multi-check : %s" % lobby.wait("SELFTEST-DONE multi-check", 60))
    time.sleep(2)
    lobby.cmd("battletower selftest report")
    combat.cmd("battletower selftest report")
    time.sleep(3)
    got = [h for h in HOOKS if "MSAce" in h]
    log("%s webhook.discord_http - %d message(s) recu(s)%s" % ("PASS" if got else "FAIL", len(HOOKS), (" : " + got[0][:200]) if got else ""))
lobby.stop()
combat.stop()
hook_srv.shutdown()
