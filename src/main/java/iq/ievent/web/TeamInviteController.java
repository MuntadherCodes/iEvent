package iq.ievent.web;

import iq.ievent.domain.User;
import iq.ievent.service.TeamService;
import iq.ievent.service.UserService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Public landing page for accepting a team invite (see TeamService.invite:
 *  the owner can invite anyone by email, whether or not they have an
 *  account yet). The GET page (/invite/{token}) is reachable while logged
 *  out — it shows sign-in/register links that loop back here via ?next= —
 *  but actually joining (/invite/{token}/accept) requires authentication,
 *  and TeamService.acceptInvite further checks the accepting user's email
 *  matches the invite before granting access. */
@Controller
public class TeamInviteController {

    private final TeamService teamService;
    private final UserService userService;

    public TeamInviteController(TeamService teamService, UserService userService) {
        this.teamService = teamService;
        this.userService = userService;
    }

    @GetMapping("/invite/{token}")
    public String show(@PathVariable String token, @AuthenticationPrincipal UserDetails principal, Model model) {
        var view = teamService.lookupInvite(token);
        model.addAttribute("invite", view.orElse(null));
        model.addAttribute("token", token);
        if (principal != null) {
            User me = userService.byEmail(principal.getUsername());
            String email = me == null ? null : me.getEmail();
            model.addAttribute("loggedInEmail", email);
            model.addAttribute("emailMatches", email != null && view.isPresent() && email.equalsIgnoreCase(view.get().email()));
        }
        return "invite";
    }

    @PostMapping("/invite/{token}/accept")
    public String accept(@PathVariable String token, @AuthenticationPrincipal UserDetails principal,
                         RedirectAttributes redirect) {
        User me = userService.byEmail(principal.getUsername());
        String error = teamService.acceptInvite(token, me);
        if (error != null) {
            redirect.addFlashAttribute("inviteError", error);
            return "redirect:/invite/" + token;
        }
        redirect.addFlashAttribute("joined", true);
        return "redirect:/host";
    }
}
