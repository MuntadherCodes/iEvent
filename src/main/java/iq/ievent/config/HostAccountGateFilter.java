package iq.ievent.config;

import iq.ievent.domain.Organization;
import iq.ievent.domain.User;
import iq.ievent.service.HostService;
import iq.ievent.service.UserService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Locks a signed-in host out of their own console the moment the super admin
 * suspends their organization — one chokepoint instead of threading an
 * isDisabled() check through every /host/** handler. /host/start (no org yet)
 * and /host/disabled (the landing page this redirects to) are exempt.
 */
@Component
public class HostAccountGateFilter extends OncePerRequestFilter {

    private final UserService userService;
    private final HostService hostService;

    public HostAccountGateFilter(UserService userService, HostService hostService) {
        this.userService = userService;
        this.hostService = hostService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = RequestPaths.appPath(request);
        boolean underHost = path.equals("/host") || path.startsWith("/host/");
        boolean exempt = path.equals("/host/start") || path.equals("/host/disabled");
        if (underHost && !exempt) {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof UserDetails ud) {
                User user = userService.byEmail(ud.getUsername());
                Organization org = user == null ? null : hostService.organizationOf(user).orElse(null);
                if (org != null && org.isDisabled()) {
                    response.sendRedirect(request.getContextPath() + "/host/disabled");
                    return;
                }
            }
        }
        chain.doFilter(request, response);
    }
}
