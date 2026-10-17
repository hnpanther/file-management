package com.hnp.filemanagement.folder.web;

import com.hnp.filemanagement.folder.domain.FolderClassService;
import com.hnp.filemanagement.folder.domain.RoleClassGrantService;
import com.hnp.filemanagement.identity.domain.FixedRole;
import com.hnp.filemanagement.identity.domain.RoleDTO;
import com.hnp.filemanagement.identity.domain.RoleService;
import com.hnp.filemanagement.identity.security.UserDetailsImpl;
import com.hnp.filemanagement.shared.exception.InvalidDataException;
import com.hnp.filemanagement.shared.web.GlobalGeneralLogging;
import com.hnp.filemanagement.shared.web.UiMessages;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.ArrayList;
import java.util.List;

/**
 * A role's class grants (roadmap Phase 14), on a page of their own beside the role's: each a class, read or
 * write, and a scope folder or everywhere. Seen with {@code UPDATE_ROLE_PAGE}, saved with {@code SAVE_UPDATED_ROLE};
 * a fixed role is shown and never changed.
 */
@Controller
public class RoleClassGrantController {

    private static final String VIEW = "role/role-class-grants.html";

    /** Empty rows the form offers below the role's own grants. */
    private static final int SPARE_ROWS = 3;

    private final RoleClassGrantService roleClassGrantService;
    private final FolderClassService folderClassService;
    private final RoleService roleService;
    private final GlobalGeneralLogging globalGeneralLogging;
    private final UiMessages messages;

    public RoleClassGrantController(RoleClassGrantService roleClassGrantService, FolderClassService folderClassService,
                                    RoleService roleService, GlobalGeneralLogging globalGeneralLogging, UiMessages messages) {
        this.roleClassGrantService = roleClassGrantService;
        this.folderClassService = folderClassService;
        this.roleService = roleService;
        this.globalGeneralLogging = globalGeneralLogging;
        this.messages = messages;
    }

    /** One row of the form. */
    public record Row(Integer classId, String permission, String scope) {
    }

    //UPDATE_ROLE_PAGE
    @PreAuthorize("hasAuthority('UPDATE_ROLE_PAGE') || hasAuthority('ADMIN')")
    @GetMapping("/roles/{roleId}/class-grants")
    public String page(@PathVariable("roleId") int roleId, Model model) {
        globalGeneralLogging.detail("class grants of role id=" + roleId);
        List<Row> rows = new ArrayList<>(roleClassGrantService.of(roleId).stream()
                .map(grant -> new Row(grant.classId(), grant.permission().name(), grant.scope() == null ? "" : grant.scope()))
                .toList());
        fill(model, roleId, rows);
        return VIEW;
    }

    //SAVE_UPDATED_ROLE
    @PreAuthorize("hasAuthority('SAVE_UPDATED_ROLE') || hasAuthority('ADMIN')")
    @PostMapping("/roles/{roleId}/class-grants")
    public String save(@AuthenticationPrincipal UserDetailsImpl userDetails, @PathVariable("roleId") int roleId,
                       @RequestParam(value = "classId", required = false) List<String> classIds,
                       @RequestParam(value = "permission", required = false) List<String> permissions,
                       @RequestParam(value = "scope", required = false) List<String> scopes,
                       Model model, RedirectAttributes redirect) {
        globalGeneralLogging.detail("save class grants of role id=" + roleId);
        List<Row> rows = new ArrayList<>();
        int count = classIds == null ? 0 : classIds.size();
        for (int i = 0; i < count; i++) {
            rows.add(new Row(parse(classIds.get(i)), at(permissions, i), at(scopes, i)));
        }
        try {
            roleClassGrantService.replace(roleId, rows.stream()
                    .map(row -> new RoleClassGrantService.Requested(row.classId(), row.permission(), row.scope()))
                    .toList(), userDetails.getId());
            redirect.addFlashAttribute("saved", messages.get("roleClassGrant.saved"));
            return "redirect:/roles/" + roleId + "/class-grants";
        } catch (InvalidDataException e) {
            globalGeneralLogging.detail("InvalidDataException:" + e.getMessage());
            model.addAttribute("refused", messages.get(e.getMessageCode().orElse("roleClassGrant.invalid"), e.getMessageArguments()));
            fill(model, roleId, new ArrayList<>(rows.stream().filter(row -> row.classId() != null).toList()));
            return VIEW;
        }
    }

    private void fill(Model model, int roleId, List<Row> rows) {
        RoleDTO role = roleService.getRoleDtoById(roleId);
        for (int i = 0; i < SPARE_ROWS; i++) {
            rows.add(new Row(null, "READ", ""));
        }
        model.addAttribute("role", role);
        model.addAttribute("fixedRole", FixedRole.of(role.getRoleName()).isPresent());
        model.addAttribute("rows", rows);
        model.addAttribute("classes", folderClassService.all());
    }

    private static String at(List<String> values, int index) {
        return values != null && index < values.size() ? values.get(index) : null;
    }

    private static Integer parse(String value) {
        try {
            return value == null || value.isBlank() ? null : Integer.valueOf(value.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
