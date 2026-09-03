package iq.ievent.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.util.UrlPathHelper;

/**
 * One definition of "the path this request is really for". Security decisions
 * that compare paths textually MUST use the decoded, semicolon-free form:
 * getRequestURI() stays percent-encoded, so a raw startsWith("/admin") check
 * lets "/%61dmin/..." (which Spring MVC routes to /admin/...) walk straight
 * past the gate. UrlPathHelper decodes, strips ";jsessionid" style matrix
 * parameters and removes the context path.
 */
public final class RequestPaths {

    private static final UrlPathHelper HELPER = new UrlPathHelper();

    private RequestPaths() {}

    /** Decoded application path, e.g. "/admin/orgs" for "/%61dmin/orgs;x=1". */
    public static String appPath(HttpServletRequest request) {
        String p = HELPER.getPathWithinApplication(request);
        return p == null || p.isEmpty() ? "/" : p;
    }

    /** True when the decoded path is {@code prefix} itself or lives under it. */
    public static boolean under(HttpServletRequest request, String prefix) {
        String p = appPath(request);
        return p.equals(prefix) || p.startsWith(prefix + "/");
    }
}
