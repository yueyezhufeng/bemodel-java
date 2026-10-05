package com.bemodel.knowledge;

import java.util.ArrayList;
import java.util.List;

/**
 * 文档切分纯函数：按 Markdown 标题/空行段落切分，目标约 size 字、相邻重叠 overlap 字。
 * 无状态静态方法，golden 直测；参数由 KnowledgeProperties 注入（本类不管配置）。
 */
public final class Chunker {

    /** 一个片段：文档内序号 / 所属标题路径（如「缴费管理/退费流程」）/ 片段正文 */
    public record Chunk(int seq, String heading, String content) {
    }

    private Chunker() {
    }

    public static List<Chunk> split(String text, int size, int overlap) {
        List<Chunk> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        List<String> path = new ArrayList<>();
        List<Integer> levels = new ArrayList<>();
        StringBuilder para = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            String t = line.trim();
            if (t.startsWith("#")) {
                emitPara(out, para, String.join("/", path), size, overlap);
                int level = 0;
                while (level < t.length() && t.charAt(level) == '#') {
                    level++;
                }
                while (!levels.isEmpty() && levels.get(levels.size() - 1) >= level) {
                    levels.remove(levels.size() - 1);
                    path.remove(path.size() - 1);
                }
                levels.add(level);
                path.add(t.replaceAll("^#+\\s*", "").trim());
            } else if (t.isEmpty()) {
                emitPara(out, para, String.join("/", path), size, overlap);
            } else {
                if (para.length() > 0) {
                    para.append('\n');
                }
                para.append(t);
            }
        }
        emitPara(out, para, String.join("/", path), size, overlap);
        return out;
    }

    /** 段落收口：不超过 size 整段出片；超长按滑窗切（步长 size-overlap，相邻天然重叠） */
    private static void emitPara(List<Chunk> out, StringBuilder para,
                                 String heading, int size, int overlap) {
        if (para.length() == 0) {
            return;
        }
        String body = para.toString();
        para.setLength(0);
        if (body.length() <= size) {
            out.add(new Chunk(out.size(), heading, body));
            return;
        }
        int step = Math.max(size - overlap, 1);
        for (int i = 0; i < body.length(); i += step) {
            String piece = body.substring(i, Math.min(i + size, body.length()));
            if (!piece.isBlank()) {
                out.add(new Chunk(out.size(), heading, piece));
            }
            if (i + size >= body.length()) {
                break;
            }
        }
    }
}
