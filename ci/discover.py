#!/usr/bin/env python3
"""CI helper: lists dependency versions and dumps the Cobblemon / Mega Showdown APIs used by Battletower."""
import io, json, os, re, subprocess, sys, urllib.request, zipfile

OUT = sys.argv[1] if len(sys.argv) > 1 else "out/discover"
os.makedirs(OUT, exist_ok=True)
UA = {"User-Agent": "cobblepulse-battletower-ci/1.0 (github actions)"}


def get(url):
    req = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req, timeout=180) as r:
        return r.read()


def maven_versions(url):
    try:
        return re.findall(r"<version>([^<]+)</version>", get(url).decode())
    except Exception as e:
        return ["ERR " + str(e)]


lines = []


def section(title, items):
    lines.append("== " + title)
    lines.extend(items)


section("cobblemon impactdev fabric", maven_versions("https://maven.impactdev.net/repository/development/com/cobblemon/fabric/maven-metadata.xml")[-12:])
section("fabric-api 1.21.1", [v for v in maven_versions("https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/maven-metadata.xml") if v.endswith("+1.21.1")][-3:])
section("placeholder-api", [v for v in maven_versions("https://maven.nucleoid.xyz/eu/pb4/placeholder-api/maven-metadata.xml") if "1.21" in v][-12:])

SLUGS = ["cobblemon", "fabric-api", "fabric-language-kotlin", "luckperms", "placeholder-api", "cobblemon-mega-showdown"]
modrinth = {}
for slug in SLUGS:
    try:
        vs = json.loads(get(f"https://api.modrinth.com/v2/project/{slug}/version?loaders=%5B%22fabric%22%5D&game_versions=%5B%221.21.1%22%5D"))
        modrinth[slug] = vs
        items = []
        for v in vs[:8]:
            f = [f for f in v["files"] if f["primary"]] or v["files"]
            deps = ",".join(f'{d.get("project_id")}:{d.get("dependency_type")}' for d in v.get("dependencies", []))
            items.append(f'{v["version_number"]} | id={v["id"]} | {v["date_published"]} | {f[0]["filename"]} | deps={deps}')
        section("modrinth " + slug, items)
    except Exception as e:
        section("modrinth " + slug, ["ERR " + str(e)])

# Dependances requises de Mega Showdown (pour le serveur de test)
dep_projects = {}
ms = (modrinth.get("cobblemon-mega-showdown") or [None])[0]
if ms:
    for d in ms.get("dependencies", []):
        if d.get("dependency_type") != "required" or not d.get("project_id"):
            continue
        pid = d["project_id"]
        try:
            proj = json.loads(get(f"https://api.modrinth.com/v2/project/{pid}"))
            slug = proj["slug"]
            vs = json.loads(get(f"https://api.modrinth.com/v2/project/{pid}/version?loaders=%5B%22fabric%22%5D&game_versions=%5B%221.21.1%22%5D"))
            if d.get("version_id"):
                pinned = [v for v in vs if v["id"] == d["version_id"]]
                if pinned:
                    vs = pinned + [v for v in vs if v["id"] != d["version_id"]]
            dep_projects[slug] = vs
            section("mega showdown dep " + slug, [v["version_number"] for v in vs[:3]])
        except Exception as e:
            section("mega showdown dep " + pid, ["ERR " + str(e)])

with open(os.path.join(OUT, "versions.txt"), "w") as fh:
    fh.write("\n".join(lines) + "\n")
allmods = dict(modrinth)
allmods.update(dep_projects)
with open(os.path.join(OUT, "modrinth.json"), "w") as fh:
    json.dump({k: [{"version_number": v["version_number"], "id": v["id"], "files": v["files"],
                    "dependencies": v.get("dependencies", [])} for v in vs[:10]] for k, vs in allmods.items()}, fh, indent=1)
with open(os.path.join(OUT, "msdeps.json"), "w") as fh:
    json.dump(sorted(dep_projects.keys()), fh)


def pick(slug, want=None):
    for v in modrinth.get(slug, []):
        if want is None or want in v["version_number"]:
            return v
    vs = modrinth.get(slug) or []
    return vs[0] if vs else None


def download(v, name):
    f = [f for f in v["files"] if f["primary"]] or v["files"]
    path = os.path.join(OUT, name)
    with open(path, "wb") as fh:
        fh.write(get(f[0]["url"]))
    return path


def javap(jar, classes, flags=("-p",)):
    out = []
    for i in range(0, len(classes), 40):
        chunk = classes[i:i + 40]
        r = subprocess.run(["javap", *flags, "-cp", jar, *chunk], capture_output=True, text=True)
        out.append(r.stdout + r.stderr)
    return "\n".join(out)


def dump_packages(jar, names, prefixes, fname, max_depth_extra=None):
    cls = []
    for n in names:
        if not n.endswith(".class") or "$$" in n or "$lambda" in n:
            continue
        for p in prefixes:
            if n.startswith(p):
                rest = n[len(p):]
                if max_depth_extra is not None and rest.count("/") > max_depth_extra:
                    continue
                cls.append(n[:-6].replace("/", "."))
                break
    cls = sorted(set(cls))
    with open(os.path.join(OUT, fname), "w") as fh:
        fh.write(javap(jar, cls))
    return cls


# ---------------------------------------------------------------- Cobblemon
cob = pick("cobblemon", "1.8.4")
if cob:
    jar = download(cob, "cobblemon.jar")
    z = zipfile.ZipFile(jar)
    names = z.namelist()
    with open(os.path.join(OUT, "cobblemon_all_classes.txt"), "w") as fh:
        fh.write("\n".join(n for n in names if n.endswith(".class") and "$$" not in n))
    C = "com/cobblemon/mod/common/"
    dump_packages(jar, names, [C + "entity/npc/", C + "api/npc/"], "api_npc.txt")
    dump_packages(jar, names, [C + "api/battles/model/ai/", C + "battles/ai/", C + "battles/actor/", C + "api/battles/model/actor/"], "api_ai.txt")
    dump_packages(jar, names, [C + "battles/"], "api_battles_top.txt", 0)
    dump_packages(jar, names, [C + "battles/pokemon/", C + "api/battles/model/", C + "api/battles/interpreter/"], "api_battles_model.txt", 0)
    dump_packages(jar, names, [C + "api/moves/", C + "api/pokemon/stats/", C + "api/types/", C + "api/abilities/"], "api_moves_types.txt")
    dump_packages(jar, names, [C + "pokemon/", C + "api/pokemon/"], "api_pokemon.txt", 0)
    dump_packages(jar, names, [C + "api/storage/pc/", C + "api/storage/party/", C + "net/messages/client/storage/pc/",
                               C + "api/storage/PokemonStoreManager", C + "api/storage/pc/link/"], "api_storage.txt")
    dump_packages(jar, names, [C + "api/events/CobblemonEvents", C + "api/events/battles/", C + "api/events/entity/",
                               C + "api/events/pokemon/HeldItem"], "api_events.txt")
    dump_packages(jar, names, [C + "client/render/npc/", C + "client/entity/NPC", C + "client/render/models/blockbench/npc/",
                               C + "client/gui/npc/", C + "client/render/entity/npc"], "api_client_npc.txt")
    dump_packages(jar, names, [C + "util/PlayerExtensionsKt", C + "util/MiscUtilsKt", C + "util/EntityExtensionsKt",
                               C + "api/text/", C + "Cobblemon.class", C + "CobblemonItems", C + "api/pokemon/helditem/",
                               C + "battles/runner/", C + "api/battles/BattleRegistry"], "api_misc.txt")
    # Corps utiles : IA forte, lancement d'un combat PNJ, delegation du choix de l'IA
    bodies = []
    for c in ["com.cobblemon.mod.common.battles.ai.StrongBattleAI",
              "com.cobblemon.mod.common.battles.actor.NPCBattleActor",
              "com.cobblemon.mod.common.api.battles.model.actor.AIBattleActor",
              "com.cobblemon.mod.common.battles.BattleBuilder"]:
        r = subprocess.run(["javap", "-c", "-p", "-cp", jar, c], capture_output=True, text=True)
        bodies.append(f"##### {c}\n" + r.stdout[:250000])
    with open(os.path.join(OUT, "bodies.txt"), "w") as fh:
        fh.write("\n".join(bodies))
    # Iteration 2 : contextes de combat, gimmicks, regles, PNJ, PC
    dump_packages(jar, names, [C + "battles/interpreter/ContextManager", C + "api/battles/interpreter/BattleContext",
                               C + "battles/interpreter/BasicContext", C + "api/battles/interpreter/BattleMessage"], "api_context.txt")
    r = subprocess.run(["javap", "-constants", "-p", "-cp", jar, "com.cobblemon.mod.common.battles.BattleRules"], capture_output=True, text=True)
    extra_out = [r.stdout]
    for c in ["com.cobblemon.mod.common.battles.MoveActionResponse", "com.cobblemon.mod.common.battles.ShowdownMoveset$Gimmick",
              "com.cobblemon.mod.common.battles.ShowdownActionRequest", "com.cobblemon.mod.common.battles.BagItemActionResponse"]:
        r = subprocess.run(["javap", "-c", "-p", "-cp", jar, c], capture_output=True, text=True)
        extra_out.append(f"##### {c}\n" + r.stdout[:60000])
    with open(os.path.join(OUT, "bodies2.txt"), "w") as fh:
        fh.write("\n".join(extra_out))
    with open(os.path.join(OUT, "npc_data_files.txt"), "w") as fh:
        fh.write("\n".join(n for n in names if ("/npcs/" in n or "npc_presets" in n or "/dialogues/" in n) and not n.endswith(".class")))
    hits = []
    for n in names:
        if n.endswith(".class") and n.startswith("com/cobblemon/"):
            data = z.read(n)
            if b"getShowdownMessages" in data:
                hits.append(n)
    with open(os.path.join(OUT, "showdown_messages_users.txt"), "w") as fh:
        fh.write("\n".join(hits))
    for n in hits[:3]:
        r = subprocess.run(["javap", "-c", "-p", "-cp", jar, n[:-6].replace("/", ".")], capture_output=True, text=True)
        body = r.stdout
        out = []
        for m in re.finditer(r"getShowdownMessages", body):
            out.append(body[max(0, m.start() - 1500):m.start() + 600])
        with open(os.path.join(OUT, "sdm_" + n.split("/")[-1] + ".txt"), "w") as fh:
            fh.write("\n-----\n".join(out[:6]))
    pc = [n[:-6].replace("/", ".") for n in names if n.endswith(".class") and "$$" not in n and
          any(k in n for k in ("pc/link/PCLink", "OpenPCPacket", "storage/pc/PCStore.class", "PCStore$", "command/OpenPC", "PcCommand", "PCCommand"))]
    with open(os.path.join(OUT, "api_pc.txt"), "w") as fh:
        fh.write(javap(jar, sorted(set(pc))))
    cmds = [n for n in names if n.endswith(".class") and "/command/" in n and "$$" not in n]
    with open(os.path.join(OUT, "commands.txt"), "w") as fh:
        fh.write("\n".join(cmds))
    # Iteration 3 : classes PNJ (json), evenements EXP/EV, statuts
    with open(os.path.join(OUT, "npc_jsons.txt"), "w") as fh:
        for n in names:
            if (n.startswith("data/cobblemon/npcs/") or n.startswith("assets/cobblemon/bedrock/npcs/variations/")) and n.endswith(".json"):
                fh.write("### " + n + "\n" + z.read(n).decode("utf-8", "replace")[:4000] + "\n")
    dump_packages(jar, names, [C + "api/events/pokemon/ExperienceGainedEvent", C + "api/events/pokemon/EvGainedEvent",
                               C + "pokemon/status/", C + "api/pokemon/status/", C + "api/PrioritizedList", C + "api/events/Cancelable"], "api_events2.txt")
    # Iteration 4 : corps de methodes (evenement Pokedex, construction des equipes PNJ, reponses du joueur)
    out4 = []
    for c in ["com.cobblemon.mod.common.api.events.pokemon.PokemonSeenEvent",
              "com.cobblemon.mod.common.battles.interpreter.instructions.SwitchInstruction$Companion",
              "com.cobblemon.mod.common.entity.npc.NPCBattleActor",
              "com.cobblemon.mod.common.api.storage.party.PartyStore",
              "com.cobblemon.mod.common.api.battles.model.actor.BattleActor",
              "com.cobblemon.mod.common.battles.pokemon.BattlePokemon$Companion"]:
        r = subprocess.run(["javap", "-c", "-p", "-l", "-cp", jar, c], capture_output=True, text=True)
        out4.append(f"##### {c}\n" + r.stdout[:120000])
    with open(os.path.join(OUT, "bodies4.txt"), "w") as fh:
        fh.write("\n".join(out4))
    # Iteration 5 : service Showdown (contexte Graal, thread), fichiers JS du flux de combat
    out5 = []
    for c in ["com.cobblemon.mod.common.battles.runner.ShowdownService",
              "com.cobblemon.mod.common.battles.runner.ShowdownService$Companion",
              "com.cobblemon.mod.common.battles.runner.graal.GraalShowdownService",
              "com.cobblemon.mod.common.battles.ShowdownThread"]:
        r = subprocess.run(["javap", "-c", "-p", "-cp", jar, c], capture_output=True, text=True)
        out5.append(f"##### {c}\n" + r.stdout[:60000])
    with open(os.path.join(OUT, "bodies5.txt"), "w") as fh:
        fh.write("\n".join(out5))
    out6 = []
    for c in ["com.cobblemon.mod.common.client.render.npc.NPCRenderer",
              "com.cobblemon.mod.common.client.entity.NPCClientDelegate",
              "com.cobblemon.mod.common.command.ApplyPlayerTextureCommand",
              "com.cobblemon.mod.common.entity.npc.NPCPlayerTexture",
              "com.cobblemon.mod.common.entity.npc.NPCPlayerModelType",
              "com.cobblemon.mod.common.api.net.serializers.NPCPlayerTextureSerializer",
              "com.cobblemon.mod.common.net.messages.client.spawn.SpawnNPCPacket",
              "com.cobblemon.mod.common.entity.npc.NPCEntity"]:
        r = subprocess.run(["javap", "-c", "-p", "-cp", jar, c], capture_output=True, text=True)
        txt = r.stdout
        if c.endswith("NPCEntity"):
            keep, cur, buf = [], False, []
            for line in txt.splitlines():
                if line.startswith("  ") and not line.startswith("    ") and line.rstrip().endswith(";") is False and "(" in line:
                    if buf and any(k in "\n".join(buf) for k in ("NPC_PLAYER_TEXTURE", "Texture", "onSyncedDataUpdated")):
                        keep.extend(buf)
                    buf = [line]
                else:
                    buf.append(line)
            if buf and any(k in "\n".join(buf) for k in ("NPC_PLAYER_TEXTURE", "Texture")):
                keep.extend(buf)
            txt = "\n".join(keep)
        out6.append(f"##### {c}\n" + txt[:80000])
    with open(os.path.join(OUT, "bodies6.txt"), "w") as fh:
        fh.write("\n".join(out6))
    for n in names:
        if n.endswith("showdown.zip"):
            iz = zipfile.ZipFile(io.BytesIO(z.read(n)))
            for m in ["sim/battle-stream.js", "sim/cobblemon/registries.js", "sim/cobblemon/cobblemon.js", "index.js"]:
                try:
                    with open(os.path.join(OUT, "sd_" + m.replace("/", "_")), "wb") as fh:
                        fh.write(iz.read(m))
                except KeyError:
                    pass
    # Showdown embarque : regles disponibles, commandes du flux de combat
    sd = []
    for n in names:
        low = n.lower()
        if "showdown" in low and not n.endswith(".class"):
            sd.append(f"{n} {z.getinfo(n).file_size}")
    with open(os.path.join(OUT, "showdown_files.txt"), "w") as fh:
        fh.write("\n".join(sd))
    greps = []
    pats = [b"inversemod", b"Inverse Mod", b"case \"eval\"", b"case 'eval'", b"setWeather", b"terastal", b"canMegaEvo", b"canTerastallize"]

    def scan(data, label):
        for p in pats:
            idx = data.find(p)
            if idx >= 0:
                greps.append(f"{label}: {p.decode()} @ {idx}: " + data[max(0, idx - 200):idx + 300].decode("utf-8", "replace").replace("\n", " | "))

    for n in names:
        if "showdown" not in n.lower():
            continue
        data = z.read(n)
        if n.endswith(".zip"):
            try:
                iz = zipfile.ZipFile(io.BytesIO(data))
                for m in iz.namelist():
                    if m.endswith((".js", ".ts")) and any(k in m for k in ("rulesets", "battle-stream", "battle.js", "field", "conditions", "side.js")):
                        scan(iz.read(m), n + "!" + m)
                with open(os.path.join(OUT, "showdown_zip_" + os.path.basename(n) + ".txt"), "w") as fh:
                    fh.write("\n".join(iz.namelist()[:4000]))
            except Exception as e:
                greps.append(f"{n}: zip error {e}")
        elif n.endswith((".js", ".ts")):
            scan(data, n)
    with open(os.path.join(OUT, "showdown_grep.txt"), "w") as fh:
        fh.write("\n\n".join(greps))
    with open(os.path.join(OUT, "fabric.mod.json"), "wb") as fh:
        fh.write(z.read("fabric.mod.json"))
    os.remove(jar)

# ---------------------------------------------------------------- Mega Showdown
if ms:
    jar = download(ms, "megashowdown.jar")
    z = zipfile.ZipFile(jar)
    names = z.namelist()
    with open(os.path.join(OUT, "ms_files.txt"), "w") as fh:
        fh.write("\n".join(f"{n} {z.getinfo(n).file_size}" for n in names if not n.startswith("assets/") or n.endswith(".json") and "lang" in n))
    try:
        with open(os.path.join(OUT, "ms_fabric.mod.json"), "wb") as fh:
            fh.write(z.read("fabric.mod.json"))
    except KeyError:
        pass
    keys = ("gimmick", "tera", "mega", "battle", "showdown", "config", "api", "event", "handler", "npc", "util", "mixin")
    cls = sorted({n[:-6].replace("/", ".") for n in names if n.endswith(".class") and "$$" not in n
                  and any(k in n.lower() for k in keys) and "/client/" not in n})
    with open(os.path.join(OUT, "ms_api.txt"), "w") as fh:
        fh.write(javap(jar, cls[:600]))
    # Fichiers showdown modifies par Mega Showdown
    greps = []
    for n in names:
        if n.endswith((".js", ".ts")):
            data = z.read(n)
            greps.append(f"### {n} ({len(data)} o)\n" + data[:3000].decode("utf-8", "replace"))
    with open(os.path.join(OUT, "ms_js.txt"), "w") as fh:
        fh.write("\n\n".join(greps))
    os.remove(jar)
print("discover done")
