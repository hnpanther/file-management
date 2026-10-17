package com.hnp.filemanagement.onetime;

import com.hnp.filemanagement.identity.security.UserDetailsImpl;
import com.hnp.filemanagement.shared.exception.InvalidDataException;
import com.hnp.filemanagement.shared.web.GlobalGeneralLogging;
import com.hnp.filemanagement.shared.web.UiMessages;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * <b>A one-off, removed in the release after 2.16.1</b>: the page of {@link ContractDocumentsMove} - the
 * folder to work under, what a run would do, the run, and its progress.
 *
 * <p>Guarded by {@code ADMIN} alone, unlike every other page: a permission of its own would be a constant in
 * {@code PermissionEnum}, a row in {@code permission} and a box on the role page, each to be taken away again
 * a release later, for a tool used once by the administrator. The run itself is recorded in the action
 * history, and each file it moves in its file history.
 */
@Controller
@RequestMapping("/settings/contract-documents")
public class ContractDocumentsController {

    private static final String VIEW = "settings/contract-documents.html";

    /** Where the ERP's contracts are, as n8n files them: the bucket and the folder of the companies. */
    static final String DEFAULT_ROOT = "ERP/PartyCompany";

    private final GlobalGeneralLogging globalGeneralLogging;
    private final ContractDocumentsMove contractDocumentsMove;
    private final UiMessages messages;

    public ContractDocumentsController(GlobalGeneralLogging globalGeneralLogging,
                                       ContractDocumentsMove contractDocumentsMove, UiMessages messages) {
        this.globalGeneralLogging = globalGeneralLogging;
        this.contractDocumentsMove = contractDocumentsMove;
        this.messages = messages;
    }

    @PreAuthorize("hasAuthority('ADMIN')")
    @GetMapping
    public String page(Model model) {
        fill(model, DEFAULT_ROOT, null, null);
        return VIEW;
    }

    @PreAuthorize("hasAuthority('ADMIN')")
    @PostMapping("/preview")
    public String preview(@RequestParam(value = "root", required = false) String root, Model model) {
        globalGeneralLogging.detail("contract documents preview, root=" + root);
        try {
            fill(model, root, contractDocumentsMove.preview(root), null);
        } catch (InvalidDataException e) {
            globalGeneralLogging.detail("InvalidDataException:" + e.getMessage());
            fill(model, root, null, refusal(e));
        }
        return VIEW;
    }

    @PreAuthorize("hasAuthority('ADMIN')")
    @PostMapping("/run")
    public String run(@AuthenticationPrincipal UserDetailsImpl userDetails,
                      @RequestParam(value = "root", required = false) String root, Model model) {
        globalGeneralLogging.detail("contract documents run, root=" + root);
        try {
            if (contractDocumentsMove.start(root, userDetails.getId())) {
                return "redirect:/settings/contract-documents";
            }
            fill(model, root, null, messages.get("contractDocuments.alreadyRunning"));
        } catch (InvalidDataException e) {
            globalGeneralLogging.detail("InvalidDataException:" + e.getMessage());
            fill(model, root, null, refusal(e));
        }
        return VIEW;
    }

    private String refusal(InvalidDataException e) {
        return messages.get(e.getMessageCode().orElse("contractDocuments.invalid.root"), e.getMessageArguments());
    }

    private void fill(Model model, String root, ContractDocumentsMove.Plan plan, String refused) {
        model.addAttribute("root", root == null ? "" : root);
        model.addAttribute("plan", plan);
        model.addAttribute("progress", contractDocumentsMove.progress());
        model.addAttribute("refused", refused);
        model.addAttribute("container", ContractDocumentsMove.CONTAINER);
        model.addAttribute("target", ContractDocumentsMove.TARGET);
    }
}
