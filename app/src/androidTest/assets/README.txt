silence.mp3 and silence.flac contain 0.1 seconds of generated 8 kHz mono silence.
Generated with FFmpeg anullsrc, libmp3lame at 16 kbit/s and the FLAC encoder.
These small fixtures exercise real audio containers during Unicode tag and artwork round trips.

mosaic.dng is a synthetic 96 x 64, uncompressed 12-bit RGGB sensor image in a
16-bit DNG container, generated with NumPy and tifffile. Samples are
64 + arange(6144) % 3800; black/white levels are 64/4095, orientation is 6,
white balance is neutral and ColorMatrix1 is the D65 XYZ-to-sRGB matrix.
It exercises full-resolution RAW development, orientation and EXIF retention.
