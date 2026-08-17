package iq.ievent.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import iq.ievent.service.PasswordResetService;
import iq.ievent.service.UserService;
import jakarta.validation.Valid;
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

    public AuthController(UserService userService, PasswordResetService passwordReset) {
        this.userService = userService;
        this.passwordReset = passwordReset;
    }

    @GetMapping("/login")
    public String login() {
        return "auth/login";
    }

    @GetMapping("/register")
    public String register(Model model) {
        model.addAttribute("form", new RegisterForm());
        return "auth/register";
    }

    @PostMapping("/register")
    public String register(@Valid @ModelAttribute("form") RegisterForm form,
                           BindingResult binding,
                           @RequestParam(name = "terms", required = false) String terms,
                           Model model) {
        if (!binding.hasFieldErrors("email") && userService.emailTaken(form.getEmail())) {
            binding.rejectValue("email", "email.taken", "An account with this email already exists");
        }
        boolean termsAccepted = terms != null;
        if (!termsAccepted) {
            model.addAttribute("termsError", "Please accept the Terms of Service and Privacy Policy to continue.");
        }
        if (binding.hasErrors() || !termsAccepted) {
            return "auth/register";
        }
        userService.register(form.getFullName(), form.getEmail(), form.getPhone(), form.getPassword());
        return "redirect:/auth/login?registered";
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
            model.addAttribute("resetError", "Password must be at least 8 characters.");
            return "auth/reset";
        }
        if (!pass.equals(confirm)) {
            model.addAttribute("resetError", "Passwords don't match. Please re-type them.");
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
