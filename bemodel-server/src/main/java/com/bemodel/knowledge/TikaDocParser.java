package com.bemodel.knowledge;

import com.bemodel.common.BizException;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.Locale;

/** 文档解析封装：Tika 一站式提取纯文本（MD/PDF/DOCX/TXT）；扫描件 OCR 明确不做 */
@Slf4j
@Component
public class TikaDocParser {

    public String parse(InputStream in) {
        try (InputStream source = in) {
            BodyContentHandler handler = new BodyContentHandler(-1);
            new AutoDetectParser().parse(source, handler, new Metadata(), new ParseContext());
            String text = handler.toString();
            if (text.isBlank()) {
                // Tika 对截断流（如残缺 %PDF/PK 头）偏宽容：不抛异常但零文本产出。
                // 零文本=解析失败（损坏或纯扫描件），在解析口直接拒收，不留空文档进库。
                throw new BizException("文档解析失败：未提取到任何文本，文件可能已损坏或为纯扫描件");
            }
            return text;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("文档解析失败: {}", e.getMessage());
            throw new BizException("文档解析失败，请确认文件未损坏且为 .md/.pdf/.docx/.txt 格式: "
                    + e.getMessage());
        }
    }

    /** 后缀 → 文档类型；不认识的后缀直接拒收（白名单而非猜测） */
    public String docTypeOf(String filename) {
        if (filename == null || !filename.contains(".")) {
            throw new BizException("无法识别文件类型，仅支持 .md/.pdf/.docx/.txt");
        }
        String ext = filename.substring(filename.lastIndexOf('.') + 1).toUpperCase(Locale.ROOT);
        return switch (ext) {
            case "MD", "PDF", "DOCX", "TXT" -> ext;
            default -> throw new BizException("暂不支持 ." + ext + " 格式，仅支持 .md/.pdf/.docx/.txt");
        };
    }
}
