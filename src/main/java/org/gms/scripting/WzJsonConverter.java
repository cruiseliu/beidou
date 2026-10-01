package org.gms.scripting;

import com.alibaba.fastjson2.JSON;
import org.gms.provider.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * wz 节点整树 → JSON 的无语义转换器（脚本消费 wz 的通用原语，见 doc/10）。
 *
 * 不理解 wz 的任何结构约定（没有 info/spec/time 概念）：imgdir → 对象（子节点按名），
 * Number/String 标量原样（float 保真），其余节点类型（画布/向量等）与空子树省略。
 * 无状态、无缓存——缓存归调用方（解析产物归谁用就归谁缓存）。
 */
public final class WzJsonConverter {

    private WzJsonConverter() {
    }

    /** 节点整树 JSON；null 节点返回 null，空树返回 "{}"。JS 侧 JSON.parse 后即纯 JS 对象。 */
    public static String convert(Data node) {
        if (node == null) {
            return null;
        }
        Object root = toJson(node);
        return root == null ? "{}" : JSON.toJSONString(root);
    }

    /** 节点 → JSON 值：标量原样；有标量后代 → 子节点对象；空树/非标量叶子 → null（省略） */
    private static Object toJson(Data node) {
        Object raw = node.getData();
        if (raw instanceof Number || raw instanceof String) {
            return raw;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Data child : node.getChildren()) {
            Object value = toJson(child);
            if (value != null) {
                out.put(child.getName(), value);
            }
        }
        return out.isEmpty() ? null : out;
    }
}
