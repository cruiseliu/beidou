import org.graalvm.polyglot.Context;

import java.util.Arrays;
import java.util.List;

/**
 * 一次性探针：验证 GraalJS 把 JS 数组传给 Java 各签名形态时的自动转换行为
 * （gainItems([[id, qty], ...]) 脚本 API 的参数选型依据）。跑完即删。
 */
public class GraalListVarargsProbe {

    public static class Api {

        /** 待验证主案：List<Integer>... varargs */
        public String gainItems(List<Integer>... pairs) {
            return dump("varargs", pairs);
        }

        /** 对照：List<List<Integer>> 单参（泛型擦除下嵌套转换存疑） */
        public String gainItemsNested(List<List<Integer>> pairs) {
            return dump("nested", pairs);
        }

        /** 对照：int[][]（原始数组二层） */
        public String gainItemsPrimitive(int[][] pairs) {
            StringBuilder sb = new StringBuilder("primitive args=" + pairs.length);
            for (int[] pair : pairs) {
                sb.append(" ").append(Arrays.toString(pair));
            }
            return sb.toString();
        }

        private String dump(String tag, List<?>... groups) {
            StringBuilder sb = new StringBuilder(tag + " args=" + groups.length);
            for (List<?> group : groups) {
                sb.append(" [n=").append(group.size());
                for (Object o : group) {
                    sb.append(" ").append(o).append(":").append(o == null ? "null" : o.getClass().getSimpleName());
                }
                sb.append("]");
            }
            return sb.toString();
        }
    }

    public static void main(String[] args) {
        Api api = new Api();
        try (Context ctx = Context.newBuilder("js").allowAllAccess(true).build()) {
            ctx.getBindings("js").putMember("api", api);

            check(ctx, "A varargs 逐对      ", "api.gainItems([2010007, 1], [2010009, 3])");
            check(ctx, "B varargs 单参外层数组", "api.gainItems([[2010007, 1], [2010009, 3]])");
            check(ctx, "C varargs spread     ", "api.gainItems(...[[2010007, 1], [2010009, 3]])");
            check(ctx, "D nested 单参        ", "api.gainItemsNested([[2010007, 1], [2010009, 3]])");
            check(ctx, "E int[][] 单参       ", "api.gainItemsPrimitive([[2010007, 1], [2010009, 3]])");
        }
    }

    private static void check(Context ctx, String label, String js) {
        String result;
        try {
            result = ctx.eval("js", js).asString();
        } catch (Throwable t) {
            result = "THROW " + t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage()).lines().findFirst().orElse("");
        }
        System.out.println(label + " => " + result);
    }
}
