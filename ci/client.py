#!/usr/bin/env python3
"""CI : lance le client (xvfb), ouvre l'editeur de PNJ sur l'ecran titre et recupere les captures d'ecran."""
import glob, os, shutil, subprocess, sys

OUT = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else "out")
dst = os.path.join(OUT, "client")
os.makedirs(dst, exist_ok=True)
subprocess.run("sudo apt-get install -y -qq xvfb mesa-utils libgl1-mesa-dri > /dev/null 2>&1 || true", shell=True)
env = dict(os.environ, LIBGL_ALWAYS_SOFTWARE="1", MESA_GL_VERSION_OVERRIDE="4.5")
with open(os.path.join(dst, "client.log"), "w") as logf:
    try:
        subprocess.run(["xvfb-run", "-a", "-s", "-screen 0 1280x720x24", "./gradlew", "runClient", "--no-daemon", "--stacktrace"],
                       stdout=logf, stderr=subprocess.STDOUT, env=env, timeout=1500)
    except subprocess.TimeoutExpired:
        logf.write("\n!! timeout\n")
for f in glob.glob("run-client/screenshots/*.png"):
    shutil.copy(f, dst)
for f in glob.glob("run-client/logs/latest.log"):
    shutil.copy(f, os.path.join(dst, "latest.log"))
print("client screenshots:", os.listdir(dst))
