import binascii
import struct
import unittest
import zlib
from android_large_image import pattern_png, CASES


class LargeImageFixtureTest(unittest.TestCase):
    def test_dimensions_and_rgb_pixels_are_real(self):
        data=pattern_png(513,3)
        self.assertEqual(b'\x89PNG\r\n\x1a\n',data[:8])
        self.assertEqual((513,3),struct.unpack('>II',data[16:24]))
        pos=8;compressed=b''
        while pos<len(data):
            length=struct.unpack('>I',data[pos:pos+4])[0];kind=data[pos+4:pos+8];body=data[pos+8:pos+8+length]
            self.assertEqual(binascii.crc32(kind+body)&0xffffffff,struct.unpack('>I',data[pos+8+length:pos+12+length])[0])
            if kind==b'IDAT':compressed+=body
            pos+=12+length
        pixels=zlib.decompress(compressed)
        self.assertEqual((513*3+1)*3,len(pixels))
        self.assertNotEqual(pixels[1:4],pixels[1+256*3:4+256*3])

    def test_repeated_generation_matches_exactly(self):
        self.assertEqual(pattern_png(300,300,1),pattern_png(300,300,1))
        self.assertNotEqual(pattern_png(300,300,1),pattern_png(300,300,2))

    def test_cases_include_a_256_mib_unscaled_image(self):
        self.assertIn(('square8192',8192,8192),CASES)
        self.assertEqual(256*1024*1024,8192*8192*4)

    def test_invalid_dimensions_fail(self):
        for width,height in [(0,1),(1,0),(8193,1),(1,8193)]:
            with self.assertRaises(ValueError):pattern_png(width,height)


if __name__=='__main__':unittest.main()
