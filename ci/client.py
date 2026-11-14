#!/usr/bin/env python3
"""CI : lance un vrai client Fabric 1.21.1 (environnement de production, sous xvfb) avec Cobblemon + Battletower,
ouvre l'editeur de PNJ sur l'ecran titre (auto-test) et recupere les captures d'ecran."""
import glob, json, os, shutil, subprocess, sys, urllib.request

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


subprocess.run("sudo apt-get install -y -qq xvfb mesa-utils libgl1-mesa-dri > /dev/null 2>&1 || true", shell=True)
run([sys.executable, "-m", "pip", "install", "-q", "portablemc==4.4.1"], 300)
modrinth = json.load(open(os.path.join(OUT, "discover", "modrinth.json")))
for slug, ver in {"fabric-api": None, "fabric-language-kotlin": None, "cobblemon": "1.8.4"}.items():
    for v in modrinth.get(slug) or []:
        if ver is None or ver in v["version_number"]:
            f = [f for f in v["files"] if f["primary"]] or v["files"]
            open(os.path.join(MC, "mods", f[0]["filename"]), "wb").write(get(f[0]["url"]))
            log.write(f"mod {slug} {v['version_number']}\n")
            break
for j in [j for j in glob.glob("build/libs/*.jar") if not j.endswith("-sources.jar")]:
    shutil.copy(j, os.path.join(MC, "mods"))
version = "fabric:1.21.1:" + props.get("loader_version", "0.19.5")
base = [sys.executable, "-m", "portablemc", "--main-dir", MC, "--work-dir", MC]
java = shutil.which("java") or "java"
run(base + ["start", "--dry", "--jvm", java, version], 900)
env = dict(os.environ, LIBGL_ALWAYS_SOFTWARE="1", MESA_GL_VERSION_OVERRIDE="4.5")
run(["xvfb-run", "-a", "-s", "-screen 0 1280x720x24"] + base + ["start", "-u", "BTClientTest", "--resolution", "1280x720", "--jvm", java,
     "--jvm-args=-Xmx3G -Dcobblepulse_battletower.clienttest=true", version], 1200, env)
for f in glob.glob(os.path.join(MC, "screenshots", "*.png")):
    shutil.copy(f, DST)
for f in glob.glob(os.path.join(MC, "logs", "latest.log")):
    shutil.copy(f, os.path.join(DST, "latest.log"))
for f in glob.glob(os.path.join(MC, "crash-reports", "*")):
    shutil.copy(f, DST)
log.close()
print("client:", os.listdir(DST))
