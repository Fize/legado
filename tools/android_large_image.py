"""Deterministic local PNG fixtures; compression stays small while decode dimensions are large."""
import binascii
import hashlib
import struct
import zlib

CASES = [('square4096', 4096, 4096), ('square8192', 8192, 8192), ('tall8192', 2048, 8192)]


def chunk(kind, data):
    return struct.pack('>I', len(data))+kind+data+struct.pack('>I', binascii.crc32(kind+data)&0xffffffff)


def pattern_png(width, height, variant=0):
    if not 1 <= width <= 8192 or not 1 <= height <= 8192:
        raise ValueError('Fixture dimensions out of bounds')
    palettes = [(230,40,40),(30,180,70),(35,80,230),(245,210,30),(180,40,220),(20,200,210)]
    compressor=zlib.compressobj(6)
    parts=[]
    rows={}
    for y in range(height):
        band=(y//256+variant)%len(palettes)
        if band not in rows:
            row=bytearray()
            for x in range(0,width,256):
                color=palettes[(band+x//256)%len(palettes)]
                row.extend(bytes(color)*min(256,width-x))
            rows[band]=b'\0'+row
        parts.append(compressor.compress(rows[band]))
    parts.append(compressor.flush())
    header=struct.pack('>IIBBBBB',width,height,8,2,0,0,0)
    return b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',header)+chunk(b'IDAT',b''.join(parts))+chunk(b'IEND',b'')


def create_fixtures(directory):
    directory.mkdir(parents=True,exist_ok=True)
    result=[]
    for index,(name,width,height) in enumerate(CASES):
        data=pattern_png(width,height,index)
        path=directory/(name+'.png');path.write_bytes(data)
        result.append({'name':name,'file':path.name,'width':width,'height':height,'encoded_bytes':len(data),
                       'unscaled_argb8888_bytes':width*height*4,'sha256':hashlib.sha256(data).hexdigest()})
    return result
