#!/usr/bin/env python3
"""CI : demarre un vrai serveur Fabric 1.21.1 (Cobblemon 1.8.4 + Mega Showdown + Battletower) et lance les auto-tests."""
import glob, json, os, queue, shutil, subprocess, sys, threading, time, urllib.request

OUT = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else "out")
DISC = os.path.join(OUT, "discover", "modrinth.json")
SRV = os.path.abspath("run-server")
UA = {"User-Agent": "cobblepulse-battles-ci/2.0 (github actions)"}
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

for j in [j for j in (glob.glob("build/libs/*.jar") or glob.glob(os.path.join(OUT, "cobblepulse-battles-*.jar"))) if not j.endswith("-sources.jar")]:
    shutil.copy(j, os.path.join(SRV, "mods"))
    log("mod battles " + j)

# Datapack de test : un fakemon dans un autre espace de noms (btfake:testmon_ex), derive de l'espece Pikachu de Cobblemon
try:
    import zipfile
    cj = [j for j in glob.glob(os.path.join(SRV, "mods", "*.jar")) if "cobblemon-fabric" in os.path.basename(j).lower()
          or os.path.basename(j).lower().startswith("cobblemon-")]
    cj = [j for j in cj if "mega" not in j.lower()]
    z = zipfile.ZipFile(cj[0])
    src = [n for n in z.namelist() if n.startswith("data/cobblemon/species/") and n.endswith("/pikachu.json")][0]
    sp = json.loads(z.read(src).decode("utf-8"))
    for k in ("forms", "evolutions", "preEvolution", "features", "aspects", "standingEyeHeight", "lightingData"):
        sp.pop(k, None)
    sp.update({"name": "Testmon Ex", "nationalPokedexNumber": 9901, "primaryType": "electric", "secondaryType": "fairy",
               "labels": ["custom"]})
    dp = os.path.join(SRV, "world", "datapacks", "btfake")
    os.makedirs(os.path.join(dp, "data", "btfake", "species", "custom"), exist_ok=True)
    open(os.path.join(dp, "pack.mcmeta"), "w").write(json.dumps({"pack": {"pack_format": 48, "description": "Battletower CI fakemon"}}))
    open(os.path.join(dp, "data", "btfake", "species", "custom", "testmon_ex.json"), "w").write(json.dumps(sp, indent=2))
    log(f"datapack fakemon btfake:testmon_ex (depuis {src} de {os.path.basename(cj[0])})")
except Exception as e:
    log(f"!! datapack fakemon : {e}")

# Ancienne configuration de Battletower : doit etre deplacee vers config/cobblepulse_battles au premier demarrage
try:
    import struct, zlib
    legacy = os.path.join(SRV, "config", "cobblepulse_battletower", "skins")
    os.makedirs(legacy, exist_ok=True)

    def chunk(t, d):
        c = struct.pack(">I", len(d)) + t + d
        return c + struct.pack(">I", zlib.crc32(t + d) & 0xffffffff)

    raw = b"".join(b"\x00" + b"\xff\x00\x00\xff" * 64 for _ in range(64))
    png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 64, 64, 8, 6, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b"")
    open(os.path.join(legacy, "Legacy-Marker.png"), "wb").write(png)
    log("ancienne configuration cobblepulse_battletower creee (migration)")
except Exception as e:
    log(f"!! ancienne configuration : {e}")

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
            if c.startswith("wait "):
                _, secs, token = c.split(" ", 2)
                t0 = time.time()
                found = wait_for(token, float(secs))
                log("wait %s : %s (%.0f s)" % (token, found, time.time() - t0))
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


TOWER = ["sleep 5", "battletower selftest", "sleep 5", "battletower selftest ascension", "sleep 85",
         "battletower selftest defeat", "sleep 45", "battletower selftest daily", "sleep 100",
         "battletower selftest extreme", "sleep 290"]
BATTLES = ["battletower selftest ranked", "wait 240 SELFTEST-DONE ranked",
           "battletower selftest rankedtimer", "wait 240 SELFTEST-DONE rankedtimer",
           "battletower selftest trainers", "wait 200 SELFTEST-DONE trainers",
           "battletower selftest towers", "wait 200 SELFTEST-DONE towers",
           "battletower selftest season", "wait 40 SELFTEST-DONE season"]
STORE = ["battletower selftest rankedstorage", "wait 40 SELFTEST-DONE rankedstorage"]
END = ["battletower selftest resume", "sleep 3", "battletower selftest report"]
CHECK = ["sleep 5", "battletower selftest persisted", "battletower selftest rankedpersisted", "sleep 3", "battletower selftest report"]
CHECK_BATTLES = ["sleep 5", "battletower selftest battlespersisted", "battletower selftest rankedpersisted", "sleep 3", "battletower selftest report"]
CFG = os.path.join(SRV, "config", "cobblepulse_battles")

mode = open("ci/QUICKSMOKE").read().strip() if os.path.exists("ci/QUICKSMOKE") else ""
if mode == "battles":
    # iteration rapide : uniquement les nouveautes de CobblePulse-Battles (stockage JSON)
    run_server("json-1", ["sleep 5", "battletower selftest"] + BATTLES + STORE + ["sleep 3", "battletower selftest report"])
    run_server("json-2", CHECK_BATTLES)
    shutil.copytree(CFG, os.path.join(OUT, "server-config-json"), dirs_exist_ok=True)
    sys.exit(0)
if os.path.exists("ci/QUICKSMOKE"):
    run_server("json-1", TOWER + BATTLES + STORE + END)
    sys.exit(0)
run_server("json-1", TOWER + BATTLES + STORE + END)
run_server("json-2", CHECK + ["battletower selftest battlespersisted", "sleep 2", "battletower selftest report"])
shutil.copytree(CFG, os.path.join(OUT, "server-config-json"), dirs_exist_ok=True)
cfg = os.path.join(CFG, "storage.toml")
if os.environ.get("BT_MYSQL") == "1":
    open(cfg, "w").write('mode = "mysql"\n[mysql]\nhost = "127.0.0.1"\nport = 3306\ndatabase = "cobblepulse"\nusername = "root"\npassword = "battletower"\n')
    run_server("mysql-1", TOWER + STORE + END)
    run_server("mysql-2", CHECK)
open(cfg, "w").write('mode = "sqlite"\n')
run_server("sqlite-1", TOWER + STORE + END)
run_server("sqlite-2", CHECK)
for crash in glob.glob(os.path.join(SRV, "crash-reports", "*")):
    shutil.copy(crash, OUT)
