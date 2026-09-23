package dev.aegis.documents.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class OriginalFormatTest {

    static byte[] jpeg() {
        return bytes(0xFF, 0xD8, 0xFF, 0xE1, 0x00, 0x10, 'E', 'x', 'i', 'f', 0, 0, 'M', 'M', 0, 0x2A);
    }

    static byte[] png() {
        return concat(bytes(0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'), bytes(0, 0, 0, 13), ascii("IHDR"),
                bytes(0, 0, 0x0F, 0xA0, 0, 0, 0x0B, 0xB8));
    }

    static byte[] webp(String chunk) {
        return concat(ascii("RIFF"), bytes(0x24, 0x10, 0, 0), ascii("WEBP"), ascii(chunk), bytes(0x18, 0, 0, 0));
    }

    static byte[] ftyp(String majorBrand, String... compatible) {
        ByteBuffer box = ByteBuffer.allocate(16 + 4 * compatible.length);
        box.putInt(16 + 4 * compatible.length).put(ascii("ftyp")).put(ascii(majorBrand)).putInt(0);
        for (String brand : compatible) {
            box.put(ascii(brand));
        }
        return box.array();
    }

    @Test
    void recognizesEveryAcceptedFormatByItsSignature() {
        assertThat(OriginalFormat.detect(ascii("%PDF-1.7\n%âãÏÓ"))).contains(OriginalFormat.PDF);
        assertThat(OriginalFormat.detect(jpeg())).contains(OriginalFormat.JPEG);
        assertThat(OriginalFormat.detect(bytes(0xFF, 0xD8, 0xFF, 0xDB))).contains(OriginalFormat.JPEG);
        assertThat(OriginalFormat.detect(png())).contains(OriginalFormat.PNG);
        assertThat(OriginalFormat.detect(webp("VP8 "))).contains(OriginalFormat.WEBP);
        assertThat(OriginalFormat.detect(webp("VP8L"))).contains(OriginalFormat.WEBP);
        assertThat(OriginalFormat.detect(webp("VP8X"))).contains(OriginalFormat.WEBP);
        assertThat(OriginalFormat.detect(ftyp("heic", "mif1", "heic"))).contains(OriginalFormat.HEIC);
        assertThat(OriginalFormat.detect(ftyp("heix", "mif1", "heix"))).contains(OriginalFormat.HEIC);
    }

    @Test
    void theNameOrAnyTextNeverMakesAFileAcceptable() {
        assertThat(OriginalFormat.detect(ascii("hello, not a photo"))).isEmpty();
        assertThat(OriginalFormat.detect(ascii("<html>Foto.jpg</html>"))).isEmpty();
        assertThat(OriginalFormat.detect(ascii(" %PDF-1.4 with a leading space"))).isEmpty();
        assertThat(OriginalFormat.detect(new byte[0])).isEmpty();
    }

    @Test
    void containersTheArchiveWouldRefuseAreNotAccepted() {
        // The archive detects these as image/heif, image/avif or image sequences and does not consume them.
        assertThat(OriginalFormat.detect(ftyp("mif1", "mif1", "heic"))).isEmpty();
        assertThat(OriginalFormat.detect(ftyp("avif", "mif1", "avif"))).isEmpty();
        assertThat(OriginalFormat.detect(ftyp("hevc", "msf1", "hevc"))).isEmpty();
        assertThat(OriginalFormat.detect(ftyp("isom", "mp41"))).as("an MP4 video").isEmpty();
        assertThat(OriginalFormat.detect(concat(ascii("RIFF"), bytes(0, 0, 0, 0), ascii("WAVEfmt ")))).isEmpty();
        assertThat(OriginalFormat.detect(bytes('G', 'I', 'F', '8', '9', 'a'))).as("GIF is not offered").isEmpty();
    }

    @Test
    void aCutOffOrDamagedHeaderIsNotAccepted() {
        assertThat(OriginalFormat.detect(bytes(0xFF, 0xD8))).isEmpty();
        assertThat(OriginalFormat.detect(Arrays.copyOf(png(), 12))).isEmpty();
        byte[] damagedPng = png();
        damagedPng[12] = 'X';
        assertThat(OriginalFormat.detect(damagedPng)).as("line endings converted in transfer").isEmpty();
        byte[] convertedPng = concat(bytes(0x89, 'P', 'N', 'G', '\n', 0x1A, '\n'), bytes(0, 0, 0, 13));
        assertThat(OriginalFormat.detect(convertedPng)).isEmpty();
        assertThat(OriginalFormat.detect(webp("JUNK"))).isEmpty();
        assertThat(OriginalFormat.detect(Arrays.copyOf(webp("VP8 "), 13))).isEmpty();
        byte[] tinyBox = ftyp("heic");
        tinyBox[3] = 8;
        assertThat(OriginalFormat.detect(tinyBox)).isEmpty();
        assertThat(OriginalFormat.detect(Arrays.copyOf(ftyp("heic"), 10))).isEmpty();
    }

    @Test
    void mediaTypesMapToTheirCanonicalExtension() {
        assertThat(OriginalFormat.ofContentType("image/jpeg")).contains(OriginalFormat.JPEG);
        assertThat(OriginalFormat.ofContentType("IMAGE/PNG; charset=binary")).contains(OriginalFormat.PNG);
        assertThat(OriginalFormat.ofContentType("image/heic").map(OriginalFormat::extension)).contains(".heic");
        assertThat(OriginalFormat.ofContentType("image/gif")).isEmpty();
        assertThat(OriginalFormat.ofContentType(null)).isEmpty();
        assertThat(OriginalFormat.JPEG.extension()).isEqualTo(".jpg");
        assertThat(OriginalFormat.JPEG.isImage()).isTrue();
        assertThat(OriginalFormat.PDF.isImage()).isFalse();
    }

    @Test
    void knownExtensionsAreRemovedFromNamesRegardlessOfCase() {
        assertThat(OriginalFormat.withoutKnownExtension("IMG_20260923_101500.JPG")).isEqualTo("IMG_20260923_101500");
        assertThat(OriginalFormat.withoutKnownExtension("Brief.jpeg")).isEqualTo("Brief");
        assertThat(OriginalFormat.withoutKnownExtension("Brief.HEIC")).isEqualTo("Brief");
        assertThat(OriginalFormat.withoutKnownExtension("Rechnung.pdf")).isEqualTo("Rechnung");
        assertThat(OriginalFormat.withoutKnownExtension("Notizen.txt")).isEqualTo("Notizen.txt");
        assertThat(OriginalFormat.withoutKnownExtension("Foto")).isEqualTo("Foto");
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = (byte) values[i];
        }
        return result;
    }

    private static byte[] concat(byte[]... parts) {
        int length = Arrays.stream(parts).mapToInt(part -> part.length).sum();
        ByteBuffer buffer = ByteBuffer.allocate(length);
        for (byte[] part : parts) {
            buffer.put(part);
        }
        return buffer.array();
    }
}
