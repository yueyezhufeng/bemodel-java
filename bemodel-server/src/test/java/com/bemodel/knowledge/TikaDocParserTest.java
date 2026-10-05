package com.bemodel.knowledge;

import com.bemodel.common.BizException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tika 解析单测：md/txt 字符串直测；pdf 用脚本生成的最小文本层样例（src/test/resources） */
class TikaDocParserTest {

    private final TikaDocParser parser = new TikaDocParser();

    @Test
    void markdownShouldExtractPlainBody() {
        String text = parser.parse(getClass().getResourceAsStream("/sample.md"));
        assertTrue(text.contains("缴费是发药的前置环节"));
        assertTrue(text.contains("退药与退费必须成对完成"));
    }

    @Test
    void plainTextShouldExtract() {
        String text = parser.parse(getClass().getResourceAsStream("/sample.txt"));
        assertTrue(text.contains("病区探视管理规定"));
    }

    @Test
    void pdfTextLayerShouldExtract() {
        String text = parser.parse(getClass().getResourceAsStream("/sample.pdf"));
        assertTrue(text.contains("Bemode knowledge track test document."), "实际解析: " + text);
        assertTrue(text.contains("Fee refund must be paired with dispense return."));
    }

    @Test
    void brokenStreamShouldThrowBizException() {
        InputStream broken = new ByteArrayInputStream(new byte[]{0x25, 0x50, 0x44, 0x46, 0x01, 0x02});
        assertThrows(BizException.class, () -> parser.parse(broken));
    }

    @Test
    void docTypeShouldMapBySuffixAndRejectUnknown() {
        assertEquals("MD", parser.docTypeOf("制度.md"));
        assertEquals("PDF", parser.docTypeOf("policy.PDF"));
        assertEquals("DOCX", parser.docTypeOf("手册.docx"));
        assertEquals("TXT", parser.docTypeOf("note.txt"));
        assertThrows(BizException.class, () -> parser.docTypeOf("扫描件.jpg"));
        assertThrows(BizException.class, () -> parser.docTypeOf("无后缀名"));
    }
}
