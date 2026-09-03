package iq.ievent.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import iq.ievent.service.PasswordResetService;
import iq.ievent.service.UserService;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequestMapping("/auth")
public class AuthController {

    public static class RegisterForm {
        @NotBlank(message = "Your name is required")
        @Size(max = 120)
        private String fullName;

        @NotBlank(message = "Email is required")
        @Email(message = "Enter a valid email address")
        @Size(max = 255)
        private String email;

        @Size(max = 32)
        private String phone;

        @NotBlank(message = "Password is required")
        @Size(min = 8, max = 72, message = "Password must be at least 8 characters")
        private String password;

        public String getFullName() { return fullName; }
        public void setFullName(String fullName) { this.fullName = fullName; }
        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
        public String getPhone() { return phone; }
        public void setPhone(String phone) { this.phone = phone; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }

    private final UserService userService;
    private final PasswordResetService passwordReset;
    private final MessageSource messages;
    private final iq.ievent.service.MailService mail;

    public AuthController(UserService userService, PasswordResetService passwordReset,
                          MessageSource messages, iq.ievent.service.MailService mail) {
        this.userService = userService;
        this.passwordReset = passwordReset;
        this.messages = messages;
        this.mail = mail;
    }

    /** Localized user-facing message in the current request locale. */
    private String msg(String code, Object... args) {
        return messages.getMessage(code, args, LocaleContextHolder.getLocale());
    }

    /**
     * Open-redirect-safe continuation target ("come back after sign-in").
     * Only same-site absolute paths pass: must start with "/", never "//",
     * never a scheme ("://") and no backslash tricks. Anything else → null.
     * The success handler (SecurityConfig.HostAwareSuccessHandler) re-validates
     * the session attribute before redirecting, so this is defense in depth.
     */
    static String safeNext(String next) {
        if (next == null) return null;
        String n = next.trim();
        if (n.isEmpty() || !n.startsWith("/") || n.startsWith("//")
                || n.contains("://") || n.contains("\\")) {
            return null;
        }
        return n;
    }

    /**
     * Stores a valid ?next= in the session as LOGIN_NEXT (picked up by the
     * login success handler) and exposes it to the template as "nextParam"
     * so login ⇄ register links can carry it. Without a fresh param, an
     * already-stored LOGIN_NEXT keeps threading through (e.g. after a failed
     * login attempt redirects back to /auth/login?error without the param).
     */
    private void rememberNext(String next, HttpSession session, Model model) {
        String clean = safeNext(next);
        if (clean != null) {
            session.setAttribute("LOGIN_NEXT", clean);
        } else {
            Object existing = session.getAttribute("LOGIN_NEXT");
            clean = existing instanceof String s ? safeNext(s) : null;
        }
        model.addAttribute("nextParam", clean == null ? "" : clean);
    }

    @GetMapping("/login")
    public String login(@RequestParam(required = false) String next,
                        HttpSession session, Model model) {
        rememberNext(next, session, model);
        return "auth/login";
    }

    @GetMapping("/register")
    public String register(@RequestParam(required = false) String next,
                           HttpSession session, Model model) {
        rememberNext(next, session, model);
        model.addAttribute("form", new RegisterForm());
        return "auth/register";
    }

    @PostMapping("/register")
    public String register(@Valid @ModelAttribute("form") RegisterForm form,
                           BindingResult binding,
                           @RequestParam(name = "terms", required = false) String terms,
                           HttpSession session,
                           Model model) {
        rememberNext(null, session, model); // keep the "Sign in" link threading on error re-renders
        if (!binding.hasFieldErrors("email") && userService.emailTaken(form.getEmail())) {
            binding.rejectValue("email", "email.taken", "An account with this email already exists");
        }
        boolean termsAccepted = terms != null;
        if (!termsAccepted) {
            model.addAttribute("termsError", msg("auth.terms.required"));
        }
        if (binding.hasErrors() || !termsAccepted) {
            return "auth/register";
        }
        userService.register(form.getFullName(), form.getEmail(), form.getPhone(), form.getPassword());
        // R31 #4: welcome + where to start (async, never blocks the redirect)
        mail.sendWelcome(form.getEmail().trim(), form.getFullName(), LocaleContextHolder.getLocale());
        Object attr = session.getAttribute("LOGIN_NEXT");
        String next = attr instanceof String s ? safeNext(s) : null;
        return "redirect:/auth/login?registered" + (next == null ? ""
                : "&next=" + java.net.URLEncoder.encode(next, java.nio.charset.StandardCharsets.UTF_8));
    }

    // ---- Forgot password ----

    @GetMapping("/forgot")
    public String forgot() {
        return "auth/forgot";
    }

    @PostMapping("/forgot")
    public String forgot(@RequestParam(required = false) String email, Model model) {
        passwordReset.requestReset(email); // silently succeeds even for unknown emails
        model.addAttribute("sent", true);
        model.addAttribute("sentTo", email == null ? "" : email.trim());
        return "auth/forgot";
    }

    // ---- Reset password (link from email: /auth/reset?token=...) ----

    @GetMapping("/reset")
    public String reset(@RequestParam(required = false) String token, Model model) {
        boolean valid = token != null && passwordReset.userForToken(token).isPresent();
        model.addAttribute("token", token == null ? "" : token);
        model.addAttribute("tokenValid", valid);
        return "auth/reset";
    }

    @PostMapping("/reset")
    public String reset(@RequestParam(required = false) String token,
                        @RequestParam(required = false) String password,
                        @RequestParam(required = false) String confirm,
                        Model model) {
        boolean valid = token != null && passwordReset.userForToken(token).isPresent();
        model.addAttribute("token", token == null ? "" : token);
        model.addAttribute("tokenValid", valid);
        if (!valid) {
            return "auth/reset";
        }
        String pass = password == null ? "" : password;
        if (pass.length() < 8 || pass.length() > 72) {
            model.addAttribute("resetError", msg("auth.reset.tooShort"));
            return "auth/reset";
        }
        if (!pass.equals(confirm)) {
            model.addAttribute("resetError", msg("auth.reset.mismatch"));
            return "auth/reset";
        }
        boolean ok = passwordReset.resetPassword(token, pass);
        if (!ok) {
            model.addAttribute("tokenValid", false);
            return "auth/reset";
        }
        return "redirect:/auth/login?reset";
    }
}
