#!/usr/bin/env python3
"""CI : demarre un vrai serveur Fabric 1.21.1 (Cobblemon 1.8.4 + Mega Showdown + Battletower) et lance les auto-tests."""
import glob, json, os, queue, shutil, subprocess, sys, threading, time, urllib.request

OUT = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else "out")
DISC = os.path.join(OUT, "discover", "modrinth.json")
SRV = os.path.abspath("run-server")
UA = {"User-Agent": "cobblepulse-battletower-ci/1.0 (github actions)"}
props = dict(l.strip().split("=", 1) for l in open("gradle.properties") if "=" in l and not l.startswith("#"))


def get(url):
    req = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req, timeout=300) as r:
        return r.read()


def log(msg):
    print(msg, flush=True)
    with open(os.path.join(OUT, "smoke-steps.txt"), "a") as fh:
        fh.write(msg + "\n")


os.makedirs(os.path.join(SRV, "mods"), exist_ok=True)
modrinth = json.load(open(DISC))
wanted = {"fabric-api": None, "fabric-language-kotlin": None, "luckperms": None, "placeholder-api": None, "cobblemon": "1.8.4",
          "cobblemon-mega-showdown": "1.8.4"}
for dep in json.load(open(os.path.join(OUT, "discover", "msdeps.json"))):
    wanted.setdefault(dep, None)
for slug, ver in wanted.items():
    vs = modrinth.get(slug) or []
    pick = None
    for v in vs:
        if ver is None or ver in v["version_number"]:
            pick = v
            break
    if not pick:
        log(f"!! missing {slug}")
        continue
    f = [f for f in pick["files"] if f["primary"]] or pick["files"]
    open(os.path.join(SRV, "mods", f[0]["filename"]), "wb").write(get(f[0]["url"]))
    log(f"mod {slug} {pick['version_number']} -> {f[0]['filename']}")

for j in [j for j in glob.glob("build/libs/*.jar") if not j.endswith("-sources.jar")]:
    shutil.copy(j, os.path.join(SRV, "mods"))
    log("mod battletower " + j)

installer = json.loads(get("https://meta.fabricmc.net/v2/versions/installer"))[0]["version"]
loader = props.get("loader_version")
open(os.path.join(SRV, "server.jar"), "wb").write(get(f"https://meta.fabricmc.net/v2/versions/loader/1.21.1/{loader}/{installer}/server/jar"))
open(os.path.join(SRV, "eula.txt"), "w").write("eula=true\n")
open(os.path.join(SRV, "server.properties"), "w").write(
    "online-mode=false\nview-distance=4\nsimulation-distance=4\nspawn-protection=0\nmax-tick-time=-1\nlevel-seed=battletower\nsync-chunk-writes=false\n")


def run_server(label, commands):
    log("===== run %s =====" % label)
    proc = subprocess.Popen(["java", "-Xmx5G", "-Dbattletower.selftest=true", "-jar", "server.jar", "nogui"], cwd=SRV,
                            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)
    lines = queue.Queue()
    logf = open(os.path.join(OUT, "server-%s.log" % label), "w")

    def reader():
        for line in proc.stdout:
            logf.write(line)
            logf.flush()
            lines.put(line)
        lines.put(None)

    threading.Thread(target=reader, daemon=True).start()

    def wait_for(token, timeout):
        end = time.time() + timeout
        while time.time() < end:
            try:
                line = lines.get(timeout=1)
            except queue.Empty:
                continue
            if line is None:
                return False
            if token in line:
                return True
        return False

    ok = wait_for("Done (", 1200)
    log("server ready: %s" % ok)
    if ok:
        for c in commands:
            if c.startswith("sleep "):
                time.sleep(float(c.split()[1]))
                continue
            log("> " + c)
            proc.stdin.write(c + "\n")
            proc.stdin.flush()
            time.sleep(2)
        time.sleep(5)
    try:
        proc.stdin.write("stop\n")
        proc.stdin.flush()
    except Exception:
        pass
    try:
        proc.wait(timeout=180)
    except subprocess.TimeoutExpired:
        proc.kill()
    logf.close()
    for l in open(os.path.join(OUT, "server-%s.log" % label), errors="replace"):
        if "SELFTEST SUMMARY" in l or "FAIL " in l:
            log("[%s] %s" % (label, l.strip()))
    log("exit code %s" % proc.returncode)


FULL = ["sleep 5", "battletower selftest", "sleep 5", "battletower selftest ascension", "sleep 85",
        "battletower selftest defeat", "sleep 45", "battletower selftest daily", "sleep 45",
        "battletower selftest extreme", "sleep 115", "battletower selftest resume", "sleep 3", "battletower selftest report"]
CHECK = ["sleep 5", "battletower selftest persisted", "sleep 3", "battletower selftest report"]

run_server("json-1", FULL)
run_server("json-2", CHECK)
shutil.copytree(os.path.join(SRV, "config", "cobblepulse_battletower"), os.path.join(OUT, "server-config-json"), dirs_exist_ok=True)
cfg = os.path.join(SRV, "config", "cobblepulse_battletower", "storage.toml")
if os.environ.get("BT_MYSQL") == "1":
    open(cfg, "w").write('mode = "mysql"\n[mysql]\nhost = "127.0.0.1"\nport = 3306\ndatabase = "cobblepulse"\nusername = "root"\npassword = "battletower"\n')
    run_server("mysql-1", FULL)
    run_server("mysql-2", CHECK)
open(cfg, "w").write('mode = "sqlite"\n')
run_server("sqlite-1", FULL)
run_server("sqlite-2", CHECK)
for crash in glob.glob(os.path.join(SRV, "crash-reports", "*")):
    shutil.copy(crash, OUT)
