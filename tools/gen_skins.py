#!/usr/bin/env python3
"""Genere les skins originaux des dresseurs (64x64, format Minecraft) embarques dans le mod."""
import os, random, sys
from PIL import Image

OUT = sys.argv[1] if len(sys.argv) > 1 else "src/main/resources/assets/cobblepulse_battletower/textures/entity/npc"
os.makedirs(OUT, exist_ok=True)


def hexc(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4)) + (255,)


def shade(c, f):
    return tuple(max(0, min(255, int(v * f))) for v in c[:3]) + (c[3],)


class Skin:
    def __init__(self, seed):
        self.img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
        self.rng = random.Random(seed)

    def px(self, x, y, c, noise=True):
        if c is None:
            return
        if noise and c[3] == 255:
            n = self.rng.uniform(0.94, 1.05)
            c = shade(c, n)
        self.img.putpixel((x, y), c)

    def box(self, u, v, w, h, d, paint):
        faces = {
            "top": (u + d, v, w, d), "bottom": (u + d + w, v, w, d),
            "right": (u, v + d, d, h), "front": (u + d, v + d, w, h),
            "left": (u + d + w, v + d, d, h), "back": (u + d + w + d, v + d, w, h),
        }
        light = {"top": 1.08, "bottom": 0.7, "front": 1.0, "right": 0.86, "left": 0.86, "back": 0.8}
        for face, (fx, fy, fw, fh) in faces.items():
            for y in range(fh):
                for x in range(fw):
                    c = paint(face, x, y, fw, fh)
                    if c is not None:
                        self.px(fx + x, fy + y, shade(c, light[face]) if c[3] == 255 else c)


def make(name, p, slim=False):
    s = Skin(name)
    skin = hexc(p["skin"])
    hair = hexc(p["hair"])
    eye = hexc(p.get("eye", "#2b4c7e"))
    top = hexc(p["top"])
    top2 = hexc(p.get("top2", p["top"]))
    pants = hexc(p["pants"])
    shoes = hexc(p.get("shoes", "#2a2a2a"))
    accent = hexc(p.get("accent", "#e0b040"))
    long_hair = p.get("long", False)
    sleeves = p.get("sleeves", "long")
    cap = hexc(p["cap"]) if p.get("cap") else None
    gloves = hexc(p["gloves"]) if p.get("gloves") else None
    coat = hexc(p["coat"]) if p.get("coat") else None
    beard = hexc(p["beard"]) if p.get("beard") else None
    glasses = p.get("glasses", False)
    white = hexc("#f4f4f4")
    mouth = shade(skin, 0.72)

    def head(face, x, y, w, h):
        if face == "top":
            return hair
        if face == "bottom":
            return skin
        if face == "front":
            if y <= 1 or (y == 2 and x in (0, 1, 6, 7)):
                return hair
            if long_hair and x in (0, 7) and y <= 6:
                return hair
            if beard and y >= 5 and x not in (0, 7):
                return mouth if (y == 6 and x in (3, 4)) else beard
            if y == 4 and x in (1, 6):
                return white
            if y == 4 and x in (2, 5):
                return eye
            if glasses and y == 3 and x in (1, 2, 5, 6):
                return hexc("#1b1b1b")
            if glasses and y == 4 and x in (3, 4):
                return hexc("#1b1b1b")
            if y == 6 and x in (3, 4):
                return mouth
            if y == 3 and x in (1, 2, 5, 6):
                return shade(hair, 0.85)
            return skin
        if face == "back":
            if long_hair or y <= 6:
                return hair
            return skin
        # cotes
        if y <= 2 or long_hair:
            return hair
        if y in (4, 5) and x in (3, 4):
            return shade(skin, 0.9)
        return skin

    s.box(0, 0, 8, 8, 8, head)

    def hat(face, x, y, w, h):
        if cap is None:
            if long_hair and face in ("back",) and y >= 4:
                return hair
            return (0, 0, 0, 0)
        if face == "top":
            return cap
        if face == "bottom":
            return (0, 0, 0, 0)
        if y <= 1:
            return cap
        if face == "front" and y == 2:
            return shade(cap, 0.7)
        if face == "front" and y == 1 and x in (3, 4):
            return accent
        return (0, 0, 0, 0)

    s.box(32, 0, 8, 8, 8, hat)

    def body(face, x, y, w, h):
        if face == "top":
            return skin if x in (3, 4) and y in (1, 2) else top
        if face == "bottom":
            return pants
        if y == 9 and p.get("belt", True):
            return accent if face == "front" and x in (3, 4) else hexc("#3a2a1c")
        if y >= 10:
            return pants
        if face == "front":
            if y == 0 and x in (3, 4):
                return skin
            if x in (3, 4) and y >= 1:
                return top2
            return top
        return top

    s.box(16, 16, 8, 12, 4, body)

    def jacket(face, x, y, w, h):
        if coat is None:
            return (0, 0, 0, 0)
        if face in ("top", "bottom"):
            return (0, 0, 0, 0)
        if face == "front" and x in (3, 4) and y < 10:
            return (0, 0, 0, 0)
        return coat

    s.box(16, 32, 8, 12, 4, jacket)

    aw = 3 if slim else 4

    def arm(face, x, y, w, h):
        if face == "top":
            return top if sleeves != "none" else skin
        if face == "bottom":
            return gloves or skin
        if y >= 10:
            return gloves or skin
        if sleeves == "long" or (sleeves == "short" and y <= 3):
            return top if y < 9 else shade(top, 0.8)
        return skin

    s.box(40, 16, aw, 12, 4, arm)
    s.box(32, 48, aw, 12, 4, arm)

    def sleeve(face, x, y, w, h):
        if coat is None or face in ("top", "bottom") or y >= 10:
            return (0, 0, 0, 0)
        return coat

    s.box(40, 32, aw, 12, 4, sleeve)
    s.box(48, 48, aw, 12, 4, sleeve)

    def leg(face, x, y, w, h):
        if face == "top":
            return pants
        if face == "bottom":
            return shoes
        if y >= 10:
            return shoes
        if y == 9:
            return shade(pants, 0.8)
        return pants

    s.box(0, 16, 4, 12, 4, leg)
    s.box(16, 48, 4, 12, 4, leg)
    s.img.save(os.path.join(OUT, name + ".png"))
    return s.img


SKINS = [
    ("ace_trainer_m", False, "As (homme)", dict(skin="#e7b48f", hair="#3a2414", top="#c62f2f", top2="#f2f2f2", pants="#2c3a5a", cap="#c62f2f", accent="#f2f2f2", sleeves="short", gloves="#202020")),
    ("ace_trainer_f", True, "As (femme)", dict(skin="#f0c4a2", hair="#c25a2a", top="#2f6fc6", top2="#f2f2f2", pants="#1f1f2a", long=True, sleeves="short", accent="#f2d24a")),
    ("veteran", False, "Veteran", dict(skin="#d9a27c", hair="#bdbdbd", top="#4c5a2e", top2="#7b8a4a", pants="#3d3a2e", beard="#cfcfcf", coat="#5b4a32", accent="#c9a64a")),
    ("scientist", False, "Scientifique", dict(skin="#efc7a8", hair="#2e2e2e", top="#5a7aa8", pants="#3a3a3a", coat="#f4f4f4", glasses=True, accent="#7ad0e0")),
    ("champion", False, "Champion", dict(skin="#e4ad86", hair="#1c1c24", top="#1b1b2a", top2="#d4af37", pants="#1b1b2a", coat="#7a1020", accent="#d4af37", gloves="#f2f2f2")),
    ("shopkeeper", False, "Marchand", dict(skin="#e9b994", hair="#6b4426", top="#2f8a5a", top2="#f2f2f2", pants="#4a3a2a", accent="#f2c84a", sleeves="short", cap="#2f8a5a")),
    ("ranger_m", False, "Ranger", dict(skin="#c98e66", hair="#2a1a10", top="#3f7a3a", top2="#d8c27a", pants="#5a4a32", cap="#6b5a32", accent="#d8c27a", sleeves="long", gloves="#4a3a28")),
    ("ranger_f", True, "Ranger (femme)", dict(skin="#e6b08c", hair="#e8c45a", top="#3f7a3a", top2="#d8c27a", pants="#5a4a32", long=True, accent="#d8c27a")),
    ("black_belt", False, "Ceinture Noire", dict(skin="#d49a72", hair="#141414", top="#f2f2f2", top2="#dadada", pants="#f2f2f2", shoes="#d49a72", accent="#141414", sleeves="short", belt=True)),
    ("psychic_f", True, "Medium", dict(skin="#f2d0b8", hair="#7a3ab8", top="#3a1f5a", top2="#c07ae0", pants="#2a1640", long=True, accent="#c07ae0", coat="#5a2a8a")),
    ("hiker", False, "Montagnard", dict(skin="#cf9670", hair="#5a3a1c", top="#b8562a", top2="#e8d4a0", pants="#4a4a3a", beard="#5a3a1c", cap="#8a6a3a", accent="#e8d4a0", shoes="#5a3a1c")),
    ("gentleman", False, "Gentleman", dict(skin="#eec3a4", hair="#7a7a7a", top="#2a2a3a", top2="#f2f2f2", pants="#2a2a3a", coat="#1a1a26", accent="#b03030", gloves="#f2f2f2", beard="#8a8a8a")),
    ("tower_boss", False, "Maitre de la Tour", dict(skin="#c8916a", hair="#e6e6e6", top="#101018", top2="#7ad0ff", pants="#101018", coat="#26304a", accent="#7ad0ff", gloves="#101018", glasses=True)),
    ("rival", False, "Rival", dict(skin="#efbf98", hair="#d8a83a", top="#7a2ac6", top2="#1a1a1a", pants="#1a1a1a", accent="#f2f2f2", sleeves="short", gloves="#1a1a1a")),
]

index = ["# nom|slim|libelle (skins embarques, generes par tools/gen_skins.py)"]
sheet = Image.new("RGBA", (len(SKINS) * 72, 150), (40, 44, 52, 255))
for i, (name, slim, label, params) in enumerate(SKINS):
    img = make(name, params, slim)
    index.append(f"{name}|{'slim' if slim else 'default'}|{label}")
    # Planche de controle : vue de face (tete, corps, bras, jambes + calques)
    fig = Image.new("RGBA", (16, 32), (0, 0, 0, 0))
    aw = 3 if slim else 4
    fig.alpha_composite(img.crop((8, 8, 16, 16)), (4, 0))
    fig.alpha_composite(img.crop((40, 8, 48, 16)), (4, 0))
    fig.alpha_composite(img.crop((20, 20, 28, 32)), (4, 8))
    fig.alpha_composite(img.crop((20, 36, 28, 48)), (4, 8))
    fig.alpha_composite(img.crop((44, 20, 44 + aw, 32)), (4 - aw, 8))
    fig.alpha_composite(img.crop((44, 36, 44 + aw, 48)), (4 - aw, 8))
    fig.alpha_composite(img.crop((36, 52, 36 + aw, 64)), (12, 8))
    fig.alpha_composite(img.crop((52, 52, 52 + aw, 64)), (12, 8))
    fig.alpha_composite(img.crop((4, 20, 8, 32)), (4, 20))
    fig.alpha_composite(img.crop((20, 52, 24, 64)), (8, 20))
    sheet.alpha_composite(fig.resize((64, 128), Image.NEAREST), (i * 72 + 4, 10))
with open(os.path.join(OUT, "index.txt"), "w") as fh:
    fh.write("\n".join(index) + "\n")
sheet.save(sys.argv[2] if len(sys.argv) > 2 else "/tmp/skins_sheet.png")
print("ok", len(SKINS))
