"""Extracts each material's palette from the vanilla textures themselves.

Reading the textures rather than inventing values guarantees each wood's tone matches the
material it is named after -- that is how oak got fixed, having been far too dark.
"""
import struct
import zlib


def read_png(data):
    """Decodes an 8-bit PNG (RGB or RGBA). Returns (width, height, RGBA pixels)."""
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("not a PNG")
    pos, idat, w, h, ctype = 8, b"", 0, 0, 6
    plte, trns = b"", b""
    while pos < len(data):
        length = struct.unpack(">I", data[pos:pos + 4])[0]
        typ = data[pos + 4:pos + 8]
        body = data[pos + 8:pos + 8 + length]
        if typ == b"IHDR":
            w, h, depth, ctype = struct.unpack(">IIBB", body[:10])
            # The vanilla textures are mostly indexed (ctype 3) and some use fewer than
            # 8 bits per pixel -- acacia_planks, for instance, uses 4. A few are greyscale
            # with no palette at all: iron_ingot is one, and it is the recipe sheets rather
            # than the door textures that reach for those.
            ok = (ctype in (0, 2, 4, 6) and depth == 8) or (ctype == 3 and depth in (1, 2, 4, 8))
            if not ok:
                raise ValueError(f"unsupported format: depth={depth} ctype={ctype}")
        elif typ == b"PLTE":
            plte = body
        elif typ == b"tRNS":
            trns = body
        elif typ == b"IDAT":
            idat += body
        elif typ == b"IEND":
            break
        pos += 12 + length

    channels = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}[ctype]
    # For greyscale, tRNS names the one tone that is see-through, as a 16-bit value.
    grey_clear = struct.unpack(">H", trns[:2])[0] & 0xFF if ctype == 0 and len(trns) >= 2 else None
    raw = zlib.decompress(idat)
    stride = (w * depth + 7) // 8 if ctype == 3 else w * channels
    # Unfiltering works on bytes; below 8 bits per pixel the stride is 1 byte.
    step = max(1, (channels * depth) // 8)
    out, prev = [], bytearray(stride)
    pos = 0
    for _ in range(h):
        filt = raw[pos]
        line = bytearray(raw[pos + 1:pos + 1 + stride])
        pos += 1 + stride
        for i in range(stride):
            a = line[i - step] if i >= step else 0
            b = prev[i]
            c = prev[i - step] if i >= step else 0
            if filt == 1:
                line[i] = (line[i] + a) & 0xFF
            elif filt == 2:
                line[i] = (line[i] + b) & 0xFF
            elif filt == 3:
                line[i] = (line[i] + (a + b) // 2) & 0xFF
            elif filt == 4:
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                pred = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[i] = (line[i] + pred) & 0xFF
        for x in range(w):
            if ctype == 3:
                if depth == 8:
                    i = line[x]
                else:
                    per = 8 // depth
                    shift = 8 - depth * (x % per + 1)
                    i = (line[x // per] >> shift) & ((1 << depth) - 1)
                out.append((plte[i * 3], plte[i * 3 + 1], plte[i * 3 + 2],
                            trns[i] if i < len(trns) else 255))
            elif ctype in (0, 4):
                # Grey, and grey with alpha: one value for all three channels. A greyscale
                # image carries its transparency as a single tone named in tRNS rather than as
                # a channel -- iron_ingot's background is grey 0, and read without this the
                # ingot arrives on a solid black square.
                px = line[x * channels:(x + 1) * channels]
                alpha = px[1] if channels == 2 else (0 if grey_clear == px[0] else 255)
                out.append((px[0], px[0], px[0], alpha))
            else:
                px = line[x * channels:(x + 1) * channels]
                out.append((px[0], px[1], px[2], px[3] if channels == 4 else 255))
        prev = line
    return w, h, out


def luminance(p):
    return 0.299 * p[0] + 0.587 * p[1] + 0.114 * p[2]


def scaled(p, factor):
    return (min(255, max(0, round(p[0] * factor))),
            min(255, max(0, round(p[1] * factor))),
            min(255, max(0, round(p[2] * factor))), 255)


def palette_from(data):
    """Four tones by luminance percentile, preserving the material's natural contrast."""
    _, _, pixels = read_png(data)
    opaque = sorted((p for p in pixels if p[3] > 128), key=luminance)
    if not opaque:
        raise ValueError("texture has no opaque pixels")

    def at(q):
        return opaque[min(len(opaque) - 1, int(len(opaque) * q))][:3] + (255,)

    base = at(0.50)
    return {
        "WOOD": base,
        "WOOD_HI": at(0.88),
        "WOOD_LO": at(0.18),
        # The groove goes darker than anything in the texture, so it reads as shadow.
        "GROOVE": scaled(at(0.10), 0.72),
    }
