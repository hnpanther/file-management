package com.hnp.filemanagement.controller;

import com.hnp.filemanagement.config.security.UserDetailsImpl;
import com.hnp.filemanagement.dto.ApiKeyCreatedDTO;
import com.hnp.filemanagement.dto.ApiKeyDTO;
import com.hnp.filemanagement.exception.InvalidDataException;
import com.hnp.filemanagement.service.ApiKeyService;
import com.hnp.filemanagement.service.RoleService;
import com.hnp.filemanagement.util.GlobalGeneralLogging;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The API key screens (roadmap 9.2): list, create, edit, revoke.
 *
 * <p><b>A section of its own, with no link to the user pages.</b> A key is not a person: it is
 * issued to an integration, scoped to part of the tree, and turned off when that integration stops.
 * Managing it beside the people who log in would make both screens about something they are not.
 *
 * <p><b>Creating a key does not redirect.</b> The credential exists exactly once, in the response to
 * the POST that made it, so the list is rendered directly with it rather than after a redirect that
 * would have to carry the secret in a URL or a flash attribute to survive. The secret is never
 * stored, so if the page is lost the only remedy is a new key — which the screen says.
 */
@Controller
@RequestMapping("/api-keys")
public class ApiKeyController {

    private final GlobalGeneralLogging globalGeneralLogging;
    private final ApiKeyService apiKeyService;
    private final RoleService roleService;

    public ApiKeyController(GlobalGeneralLogging globalGeneralLogging,
                            ApiKeyService apiKeyService,
                            RoleService roleService) {
        this.globalGeneralLogging = globalGeneralLogging;
        this.apiKeyService = apiKeyService;
        this.roleService = roleService;
    }

    //GET_ALL_API_KEY_PAGE
    @PreAuthorize("hasAuthority('GET_ALL_API_KEY_PAGE') || hasAuthority('ADMIN')")
    @GetMapping
    public String getAllApiKeysPage(@AuthenticationPrincipal UserDetailsImpl userDetails,
                                    Model model) {

        globalGeneralLogging.detail("api key list page");

        model.addAttribute("apiKeys", apiKeyService.getAll());
        model.addAttribute("created", null);
        return "api-key/api-keys.html";
    }

    //CREATE_API_KEY_PAGE
    @PreAuthorize("hasAuthority('CREATE_API_KEY_PAGE') || hasAuthority('ADMIN')")
    @GetMapping("create")
    public String createApiKeyPage(@AuthenticationPrincipal UserDetailsImpl userDetails,
                                   Model model) {

        globalGeneralLogging.detail("create api key page");

        prepareForm(model, new ApiKeyDTO(), "create", false, false, "");
        return "api-key/save-api-key.html";
    }

    //SAVE_NEW_API_KEY
    @PreAuthorize("hasAuthority('SAVE_NEW_API_KEY') || hasAuthority('ADMIN')")
    @PostMapping
    public String saveNewApiKey(@AuthenticationPrincipal UserDetailsImpl userDetails,
                                @ModelAttribute("apiKey") @Validated ApiKeyDTO apiKeyDTO,
                                BindingResult bindingResult, Model model) {

        globalGeneralLogging.detail("save new api key title=" + apiKeyDTO.getTitle());

        if (bindingResult.hasErrors()) {
            prepareForm(model, apiKeyDTO, "create", true, false, "");
            return "api-key/save-api-key.html";
        }

        ApiKeyCreatedDTO created;
        try {
            created = apiKeyService.create(apiKeyDTO, userDetails.getId());
        } catch (InvalidDataException e) {
            prepareForm(model, apiKeyDTO, "create", true, false, e.getMessage());
            return "api-key/save-api-key.html";
        }

        // The one and only render that carries the secret.
        model.addAttribute("apiKeys", apiKeyService.getAll());
        model.addAttribute("created", created);
        return "api-key/api-keys.html";
    }

    //UPDATE_API_KEY_PAGE
    @PreAuthorize("hasAuthority('UPDATE_API_KEY_PAGE') || hasAuthority('ADMIN')")
    @GetMapping("{id}")
    public String updateApiKeyPage(@AuthenticationPrincipal UserDetailsImpl userDetails,
                                   @PathVariable("id") int id, Model model) {

        globalGeneralLogging.detail("edit api key page id=" + id);

        prepareForm(model, apiKeyService.getByIdWithGrants(id), "update", false, false, "");
        return "api-key/save-api-key.html";
    }

    //SAVE_UPDATED_API_KEY
    @PreAuthorize("hasAuthority('SAVE_UPDATED_API_KEY') || hasAuthority('ADMIN')")
    @PostMapping("{id}")
    public String saveUpdatedApiKey(@AuthenticationPrincipal UserDetailsImpl userDetails,
                                    @PathVariable("id") int id,
                                    @ModelAttribute("apiKey") @Validated ApiKeyDTO apiKeyDTO,
                                    BindingResult bindingResult, Model model) {

        globalGeneralLogging.detail("save updated api key id=" + id);

        if (bindingResult.hasErrors()) {
            prepareForm(model, apiKeyDTO, "update", true, false, "");
            return "api-key/save-api-key.html";
        }

        apiKeyService.update(id, apiKeyDTO, userDetails.getId());

        prepareForm(model, apiKeyService.getByIdWithGrants(id), "update", true, true, "");
        return "api-key/save-api-key.html";
    }

    //REVOKE_API_KEY
    @PreAuthorize("hasAuthority('REVOKE_API_KEY') || hasAuthority('ADMIN')")
    @PostMapping("{id}/revoke")
    public String revokeApiKey(@AuthenticationPrincipal UserDetailsImpl userDetails,
                               @PathVariable("id") int id,
                               @RequestParam(value = "enabled", required = false) String enabled,
                               Model model) {

        globalGeneralLogging.detail("revoke or switch api key id=" + id + ", enabled=" + enabled);

        // One endpoint, because both answers are "stop accepting this key" and only one of them can
        // be undone. Which it is comes from the button, not from a second permission.
        if (enabled == null) {
            apiKeyService.revoke(id, userDetails.getId());
        } else {
            apiKeyService.changeEnabled(id, "1".equals(enabled), userDetails.getId());
        }

        model.addAttribute("apiKeys", apiKeyService.getAll());
        model.addAttribute("created", null);
        return "api-key/api-keys.html";
    }

    /**
     * The form's model. The folder tree comes from {@link RoleService} — the same tree the role page
     * renders, with the same three-state control — because "which folders may this reach" is one
     * question whether the answer belongs to a role or to a key.
     */
    private void prepareForm(Model model, ApiKeyDTO apiKey, String pageType,
                             boolean showMessage, boolean valid, String message) {
        model.addAttribute("apiKey", apiKey);
        model.addAttribute("folders", roleService.getFolderTree(apiKey.getFolderGrants()));
        model.addAttribute("pageType", pageType);
        model.addAttribute("showMessage", showMessage);
        model.addAttribute("valid", valid);
        model.addAttribute("message", message);
    }
}
