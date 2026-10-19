package datphong.vn.service;

import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renderer whitelist: chỉ thay {{biến}} nằm trong allowed_variables. KHÔNG thực thi script/expression/truy vấn DB.
 * Biến được escape theo ngữ cảnh: HTML -> htmlEscape; subject/text -> bỏ CR/LF (chống header injection).
 * Biến thiếu -> MissingVariableException (email chuyển FAILED_VALIDATION), không bao giờ gửi chuỗi {{...}} cho khách.
 */
@Component
public class TemplateRenderer {

    private static final Pattern VAR = Pattern.compile("\\{\\{\\s*([A-Za-z][A-Za-z0-9_]*)\\s*}}");

    public static class MissingVariableException extends RuntimeException {
        public MissingVariableException(String v) { super("Thiếu biến: " + v); }
    }

    public record Rendered(String subject, String html, String text) {}

    public Set<String> variablesIn(String... templates) {
        Set<String> out = new TreeSet<>();
        for (String t : templates) {
            Matcher m = VAR.matcher(t == null ? "" : t);
            while (m.find()) out.add(m.group(1));
        }
        return out;
    }

    public Rendered render(String subjectT, String htmlT, String textT, Set<String> allowed, Map<String, Object> vars) {
        return new Rendered(oneLine(sub(subjectT, allowed, vars, false)), sub(htmlT, allowed, vars, true), sub(textT, allowed, vars, false));
    }

    private String sub(String template, Set<String> allowed, Map<String, Object> vars, boolean html) {
        Matcher m = VAR.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            if (!allowed.contains(name)) throw new MissingVariableException(name + " (không nằm trong danh sách cho phép)");
            Object v = vars.get(name);
            if (v == null) throw new MissingVariableException(name);
            String s = String.valueOf(v);
            m.appendReplacement(sb, Matcher.quoteReplacement(html ? HtmlUtils.htmlEscape(s) : s));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String oneLine(String s) {
        return s.replaceAll("[\\r\\n]+", " ").trim();
    }
}
