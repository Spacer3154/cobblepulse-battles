#!/usr/bin/env python3
"""CI : lance un vrai client Fabric 1.21.1 (environnement de production, sous xvfb) avec Cobblemon + Battletower,
ouvre l'editeur de PNJ sur l'ecran titre (auto-test) et recupere les captures d'ecran."""
import glob, json, os, shutil, subprocess, sys, time, urllib.request

OUT = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else "out")
DST = os.path.join(OUT, "client")
MC = os.path.abspath("run-prod-client")
os.makedirs(DST, exist_ok=True)
os.makedirs(os.path.join(MC, "mods"), exist_ok=True)
UA = {"User-Agent": "cobblepulse-battletower-ci/1.0 (github actions)"}
props = dict(l.strip().split("=", 1) for l in open("gradle.properties") if "=" in l and not l.startswith("#"))


def get(url):
    req = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req, timeout=300) as r:
        return r.read()


log = open(os.path.join(DST, "client.log"), "w")


def run(cmd, timeout, env=None):
    log.write("\n$ " + " ".join(cmd) + "\n")
    log.flush()
    try:
        return subprocess.run(cmd, stdout=log, stderr=subprocess.STDOUT, timeout=timeout, env=env).returncode
    except subprocess.TimeoutExpired:
        log.write("\n!! timeout\n")
        return -1


subprocess.run("sudo apt-get install -y -qq xvfb mesa-utils libgl1-mesa-dri x11-apps imagemagick > /dev/null 2>&1 || true", shell=True)
run([sys.executable, "-m", "pip", "install", "-q", "portablemc==4.4.1"], 300)
modrinth = json.load(open(os.path.join(OUT, "discover", "modrinth.json")))
for slug, ver in {"fabric-api": None, "fabric-language-kotlin": None, "cobblemon": "1.8.4"}.items():
    for v in modrinth.get(slug) or []:
        if ver is None or ver in v["version_number"]:
            f = [f for f in v["files"] if f["primary"]] or v["files"]
            open(os.path.join(MC, "mods", f[0]["filename"]), "wb").write(get(f[0]["url"]))
            log.write(f"mod {slug} {v['version_number']}\n")
            break
for j in [j for j in (glob.glob("build/libs/*.jar") or glob.glob(os.path.join(OUT, "cobblepulse-battles-*.jar"))) if not j.endswith("-sources.jar")]:
    shutil.copy(j, os.path.join(MC, "mods"))
version = "fabric:1.21.1:" + props.get("loader_version", "0.19.5")
base = [sys.executable, "-m", "portablemc", "--main-dir", MC, "--work-dir", MC]
java = shutil.which("java") or "java"
run(base + ["start", "--dry", "--jvm", java, version], 900)
# Premier lancement : pas d'ecran d'accessibilite, pas de son, pas de tutoriel
with open(os.path.join(MC, "options.txt"), "w") as fh:
    fh.write("onboardAccessibility:false\nnarrator:0\ntutorialStep:none\nskipMultiplayerWarning:true\njoinedFirstServer:true\n"
             "renderDistance:2\nmaxFps:30\nsoundCategory_master:0.0\n")
# Serveur de test (memes mods que le client) : le client s'y connecte apres les captures de l'editeur, puis le serveur
# ouvre chacun de ses ecrans (hub, Ranked, dresseurs...) et demande une capture.
import queue, threading
SRV = os.path.abspath("run-client-server")
shutil.copytree(os.path.join(MC, "mods"), os.path.join(SRV, "mods"), dirs_exist_ok=True)
installer = json.loads(get("https://meta.fabricmc.net/v2/versions/installer"))[0]["version"]
open(os.path.join(SRV, "server.jar"), "wb").write(get(
    f"https://meta.fabricmc.net/v2/versions/loader/1.21.1/{props.get('loader_version')}/{installer}/server/jar"))
open(os.path.join(SRV, "eula.txt"), "w").write("eula=true\n")
open(os.path.join(SRV, "server.properties"), "w").write(
    "online-mode=false\nserver-port=25570\nview-distance=4\nsimulation-distance=4\nspawn-protection=0\nmax-tick-time=-1\n"
    "level-seed=battles\nsync-chunk-writes=false\nenforce-secure-profile=false\n")
srv_log = open(os.path.join(DST, "server.log"), "w")
srv_lines = queue.Queue()
srv = subprocess.Popen(["java", "-Xmx3G", "-Dbattletower.selftest=true", "-jar", "server.jar", "nogui"], cwd=SRV, stdin=subprocess.PIPE,
                       stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)


def srv_reader():
    for line in srv.stdout:
        srv_log.write(line)
        srv_log.flush()
        srv_lines.put(line)


threading.Thread(target=srv_reader, daemon=True).start()
srv_ready = False
t_srv = time.time()
while time.time() - t_srv < 900 and srv.poll() is None:
    try:
        if "Done (" in srv_lines.get(timeout=2):
            srv_ready = True
            break
    except queue.Empty:
        pass
log.write(f"\nserveur de test pret : {srv_ready}\n")
log.flush()
joined = [False]


def srv_watch():
    while True:
        try:
            line = srv_lines.get(timeout=5)
        except queue.Empty:
            if srv.poll() is not None:
                return
            continue
        if not joined[0] and "BTClientTest joined the game" in line:
            joined[0] = True
            time.sleep(8)
            srv.stdin.write("battletower selftest clientscreens BTClientTest\n")
            srv.stdin.flush()


threading.Thread(target=srv_watch, daemon=True).start()
env = dict(os.environ, LIBGL_ALWAYS_SOFTWARE="1", MESA_GL_VERSION_OVERRIDE="4.5")
cmd = ["xvfb-run", "-n", "97", "-s", "-screen 0 1280x720x24"] + base + ["start", "-u", "BTClientTest", "--resolution", "1280x720", "--jvm", java,
       "--jvm-args=-Xmx3G -Dcobblepulse_battles.clienttest=true" + (" -Dcobblepulse_battles.clientserver=127.0.0.1:25570" if srv_ready else ""), version]
log.write("\n$ " + " ".join(cmd) + "\n")
log.flush()
proc = subprocess.Popen(cmd, stdout=log, stderr=subprocess.STDOUT, env=env)
t0 = time.time()
n = 0
while proc.poll() is None and time.time() - t0 < 1200:
    time.sleep(20)
    n += 1
    if n % 6 == 0:
        # capture de l'ecran virtuel (diagnostic si le jeu reste bloque)
        subprocess.run(f"xwd -root -display :97 -silent | convert xwd:- {DST}/xvfb_{n:03d}.png", shell=True,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
if proc.poll() is None:
    log.write("\n!! timeout : pile des fils Java\n")
    pids = subprocess.run(["pgrep", "-f", "net.fabricmc.loader"], capture_output=True, text=True).stdout.split()
    for pid in pids:
        r = subprocess.run(["jstack", pid], capture_output=True, text=True)
        with open(os.path.join(DST, f"jstack_{pid}.txt"), "w") as fh:
            fh.write(r.stdout + r.stderr)
    proc.kill()
    subprocess.run("pkill -f net.fabricmc.loader", shell=True)
for f in glob.glob(os.path.join(MC, "screenshots", "*.png")):
    shutil.copy(f, DST)
for f in glob.glob(os.path.join(MC, "logs", "latest.log")):
    shutil.copy(f, os.path.join(DST, "latest.log"))
for f in glob.glob(os.path.join(MC, "crash-reports", "*")):
    shutil.copy(f, DST)
try:
    srv.stdin.write("stop\n")
    srv.stdin.flush()
    srv.wait(timeout=120)
except Exception:
    srv.kill()
srv_log.close()
log.close()
print("client:", os.listdir(DST))
