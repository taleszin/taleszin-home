#!/usr/bin/env python3
"""Gera o ícone do launcher: bola de basquete laranja estilizada, fundo transparente.
Uso: python3 tools/make_icon.py   (escreve res/mipmap-mdpi e res/mipmap-hdpi)"""
import os
from PIL import Image, ImageDraw

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
S = 768  # desenha grande e reduz (antialias)
ORANGE = (255, 122, 26, 255)
SHADE = (214, 92, 10, 255)
LINE = (38, 20, 8, 255)

def ball():
    im = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    m = S * 0.06
    box = (m, m, S - m, S - m)
    d.ellipse(box, fill=SHADE)
    # brilho: círculo laranja levemente deslocado para cima/esquerda
    off = S * 0.035
    d.ellipse((m, m, S - m - off, S - m - off), fill=ORANGE)
    # recorta tudo ao círculo da bola
    mask = Image.new("L", (S, S), 0)
    ImageDraw.Draw(mask).ellipse(box, fill=255)
    lines = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    ld = ImageDraw.Draw(lines)
    w = int(S * 0.035)
    c = S / 2
    ld.line((c, 0, c, S), fill=LINE, width=w)          # vertical
    ld.line((0, c, S, c), fill=LINE, width=w)          # horizontal
    r = S * 0.42                                        # costuras curvas
    ld.arc((c - S * 0.62 - r, c - r, c - S * 0.62 + r, c + r), -60, 60, fill=LINE, width=w)
    ld.arc((c + S * 0.62 - r, c - r, c + S * 0.62 + r, c + r), 120, 240, fill=LINE, width=w)
    clipped = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    clipped.paste(lines, (0, 0), Image.composite(lines.split()[3], Image.new("L", (S, S), 0), mask))
    im = Image.alpha_composite(im, clipped)
    ImageDraw.Draw(im).ellipse(box, outline=LINE, width=w)
    return im

big = ball()
for folder, px in (("mipmap-mdpi", 48), ("mipmap-hdpi", 72)):
    out = os.path.join(ROOT, "res", folder, "ic_launcher.png")
    big.resize((px, px), Image.LANCZOS).save(out, optimize=True)
    print(out)
