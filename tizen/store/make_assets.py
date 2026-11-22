"""Samsung Seller Office images: 512x423 icon, 1920x1080 logo (transparent) + background.
Run from tizen/: python3 store/make_assets.py"""
import re, io
import xml.etree.ElementTree as ET
import cairosvg
from PIL import Image, ImageDraw, ImageFilter

A = '{http://schemas.android.com/apk/res/android}'
root = ET.parse('../app/src/main/res/drawable/logo_mark.xml').getroot()
vw, vh = float(root.get(A + 'viewportWidth')), float(root.get(A + 'viewportHeight'))

def color(c):
    c = c.lstrip('#')
    if len(c) == 8: return '#' + c[2:], int(c[:2], 16) / 255
    return '#' + c, 1.0

def paths(node):
    out = []
    for el in node:
        tag = el.tag
        if tag == 'group':
            tx, ty = el.get(A + 'translateX', '0'), el.get(A + 'translateY', '0')
            out.append(f'<g transform="translate({tx},{ty})">' + ''.join(paths(el)) + '</g>')
        elif tag == 'path':
            attrs = [f'd="{el.get(A + "pathData")}"']
            fc = el.get(A + 'fillColor')
            if fc: c, a = color(fc); attrs.append(f'fill="{c}" fill-opacity="{a}"')
            else: attrs.append('fill="none"')
            sc = el.get(A + 'strokeColor')
            if sc:
                c, a = color(sc)
                attrs.append(f'stroke="{c}" stroke-opacity="{a}" stroke-width="{el.get(A + "strokeWidth", "1")}" '
                             f'stroke-linecap="{el.get(A + "strokeLineCap", "butt")}" stroke-linejoin="{el.get(A + "strokeLineJoin", "miter")}"')
            out.append('<path ' + ' '.join(attrs) + '/>')
    return out

svg = f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {vw} {vh}">' + ''.join(paths(root)) + '</svg>'

def mark(width):
    png = cairosvg.svg2png(bytestring=svg.encode(), output_width=width)
    return Image.open(io.BytesIO(png)).convert('RGBA')

BG = (14, 62, 68)

def background(size):
    w, h = size
    img = Image.new('RGB', size, BG)
    glow = Image.new('L', size, 0)
    d = ImageDraw.Draw(glow)
    d.ellipse([int(w * .45), int(-h * .5), int(w * 1.15), int(h * .55)], fill=70)
    glow = glow.filter(ImageFilter.GaussianBlur(w // 12))
    gold = Image.new('RGB', size, (232, 184, 109))
    return Image.composite(gold, img, glow)

# 512x423, 24-bit PNG
icon = background((512, 423))
m = mark(330)
icon.paste(m, ((512 - m.width) // 2, (423 - m.height) // 2 - 6), m)
icon.save('store/icon-512x423.png', optimize=True)

# 1920x1080 logo on transparency (mascot centred, room around it) + background
logo = Image.new('RGBA', (1920, 1080), (0, 0, 0, 0))
m = mark(820)
logo.paste(m, ((1920 - m.width) // 2, (1080 - m.height) // 2), m)
logo.save('store/icon-1920x1080-logo.png', optimize=True)
background((1920, 1080)).save('store/icon-1920x1080-background.png', optimize=True)

# the widget's own icon too
icon.save('static/icon.png', optimize=True)

import os
for f in ['icon-512x423.png', 'icon-1920x1080-logo.png', 'icon-1920x1080-background.png']:
    print(f, os.path.getsize('store/' + f) // 1024, 'kB')
