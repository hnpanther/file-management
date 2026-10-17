package com.hnp.filemanagement.folder.web;

import com.hnp.filemanagement.folder.domain.FolderClassService;
import com.hnp.filemanagement.folder.domain.FolderClassService.ClassView;
import com.hnp.filemanagement.folder.domain.FolderClassService.Definition;
import com.hnp.filemanagement.folder.domain.FolderClassService.TemplateFolder;
import com.hnp.filemanagement.identity.security.UserDetailsImpl;
import com.hnp.filemanagement.shared.exception.DuplicateResourceException;
import com.hnp.filemanagement.shared.exception.InvalidDataException;
import com.hnp.filemanagement.shared.exception.ResourceNotFoundException;
import com.hnp.filemanagement.shared.web.GlobalGeneralLogging;
import com.hnp.filemanagement.shared.web.UiMessages;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.ArrayList;
import java.util.List;

/**
 * The folder classes (roadmap Phase 14): the list - each with its template, its rule for other children, whether
 * it is container only and how many folders carry it - and the form that defines or changes one.
 */
@Controller
@RequestMapping("/settings/folder-classes")
public class FolderClassController {

    private static final String VIEW = "settings/folder-classes.html";

    /** Empty template rows the form offers below a class's own. */
    private static final int SPARE_ROWS = 3;

    private final FolderClassService folderClassService;
    private final GlobalGeneralLogging globalGeneralLogging;
    private final UiMessages messages;

    public FolderClassController(FolderClassService folderClassService, GlobalGeneralLogging globalGeneralLogging,
                                 UiMessages messages) {
        this.folderClassService = folderClassService;
        this.globalGeneralLogging = globalGeneralLogging;
        this.messages = messages;
    }

    /** What the form shows: a class being defined ({@code id} null) or changed. */
    public record Form(Integer id, String name, String title, boolean containerOnly, String otherChildren,
                       Integer otherChildrenClassId, List<TemplateFolder> template) {
    }

    //FOLDER_CLASSES_PAGE
    @PreAuthorize("hasAuthority('FOLDER_CLASSES_PAGE') || hasAuthority('ADMIN')")
    @GetMapping
    public String list(Model model) {
        globalGeneralLogging.detail("folder classes page");
        fill(model, emptyForm());
        return VIEW;
    }

    //FOLDER_CLASSES_PAGE
    @PreAuthorize("hasAuthority('FOLDER_CLASSES_PAGE') || hasAuthority('ADMIN')")
    @GetMapping("/{id}")
    public String edit(@PathVariable("id") int id, Model model, RedirectAttributes redirect) {
        globalGeneralLogging.detail("folder class page, id=" + id);
        try {
            ClassView one = folderClassService.one(id);
            fill(model, new Form(one.id(), one.name(), one.title(), one.containerOnly(), one.otherChildren().name(),
                    one.otherChildrenClassId(), withSpareRows(one.template().stream()
                    .map(t -> new TemplateFolder(t.name(), t.displayName(), t.classId())).toList())));
            return VIEW;
        } catch (ResourceNotFoundException e) {
            redirect.addFlashAttribute("refused", messages.get("folderClass.notFound"));
            return "redirect:/settings/folder-classes";
        }
    }

    //SAVE_FOLDER_CLASS
    @PreAuthorize("hasAuthority('SAVE_FOLDER_CLASS') || hasAuthority('ADMIN')")
    @PostMapping
    public String create(@AuthenticationPrincipal UserDetailsImpl userDetails,
                         @RequestParam(value = "name", required = false) String name,
                         @RequestParam(value = "title", required = false) String title,
                         @RequestParam(value = "containerOnly", required = false) boolean containerOnly,
                         @RequestParam(value = "otherChildren", required = false) String otherChildren,
                         @RequestParam(value = "otherChildrenClassId", required = false) Integer otherChildrenClassId,
                         @RequestParam(value = "childName", required = false) List<String> childNames,
                         @RequestParam(value = "childDisplayName", required = false) List<String> childDisplayNames,
                         @RequestParam(value = "childClassId", required = false) List<String> childClassIds,
                         Model model, RedirectAttributes redirect) {
        globalGeneralLogging.detail("create folder class, name=" + name);
        Definition definition = new Definition(name, title, containerOnly, otherChildren, otherChildrenClassId,
                template(childNames, childDisplayNames, childClassIds));
        try {
            folderClassService.create(definition, userDetails.getId());
            redirect.addFlashAttribute("saved", messages.get("folderClass.saved", title == null ? "" : title.strip()));
            return "redirect:/settings/folder-classes";
        } catch (InvalidDataException | DuplicateResourceException e) {
            globalGeneralLogging.detail(e.getClass().getSimpleName() + ":" + e.getMessage());
            model.addAttribute("refused", refusal(e));
            fill(model, formOf(null, definition));
            return VIEW;
        }
    }

    //SAVE_FOLDER_CLASS
    @PreAuthorize("hasAuthority('SAVE_FOLDER_CLASS') || hasAuthority('ADMIN')")
    @PostMapping("/{id}")
    public String update(@AuthenticationPrincipal UserDetailsImpl userDetails, @PathVariable("id") int id,
                         @RequestParam(value = "name", required = false) String name,
                         @RequestParam(value = "title", required = false) String title,
                         @RequestParam(value = "containerOnly", required = false) boolean containerOnly,
                         @RequestParam(value = "otherChildren", required = false) String otherChildren,
                         @RequestParam(value = "otherChildrenClassId", required = false) Integer otherChildrenClassId,
                         @RequestParam(value = "childName", required = false) List<String> childNames,
                         @RequestParam(value = "childDisplayName", required = false) List<String> childDisplayNames,
                         @RequestParam(value = "childClassId", required = false) List<String> childClassIds,
                         Model model, RedirectAttributes redirect) {
        globalGeneralLogging.detail("update folder class id=" + id);
        Definition definition = new Definition(name, title, containerOnly, otherChildren, otherChildrenClassId,
                template(childNames, childDisplayNames, childClassIds));
        try {
            folderClassService.update(id, definition, userDetails.getId());
            redirect.addFlashAttribute("saved", messages.get("folderClass.saved", title == null ? "" : title.strip()));
            return "redirect:/settings/folder-classes";
        } catch (InvalidDataException | DuplicateResourceException | ResourceNotFoundException e) {
            globalGeneralLogging.detail(e.getClass().getSimpleName() + ":" + e.getMessage());
            model.addAttribute("refused", refusal(e));
            fill(model, formOf(id, definition));
            return VIEW;
        }
    }

    //DELETE_FOLDER_CLASS
    @PreAuthorize("hasAuthority('DELETE_FOLDER_CLASS') || hasAuthority('ADMIN')")
    @PostMapping("/{id}/delete")
    public String delete(@AuthenticationPrincipal UserDetailsImpl userDetails, @PathVariable("id") int id,
                         RedirectAttributes redirect) {
        globalGeneralLogging.detail("delete folder class id=" + id);
        try {
            folderClassService.delete(id, userDetails.getId());
            redirect.addFlashAttribute("saved", messages.get("folderClass.deleted"));
        } catch (InvalidDataException | ResourceNotFoundException e) {
            globalGeneralLogging.detail(e.getClass().getSimpleName() + ":" + e.getMessage());
            redirect.addFlashAttribute("refused", refusal(e));
        }
        return "redirect:/settings/folder-classes";
    }

    private String refusal(RuntimeException e) {
        if (e instanceof InvalidDataException invalid && invalid.getMessageCode().isPresent()) {
            return messages.get(invalid.getMessageCode().get(), invalid.getMessageArguments());
        }
        if (e instanceof DuplicateResourceException) {
            return messages.get("folderClass.invalid.nameTaken");
        }
        if (e instanceof ResourceNotFoundException) {
            return messages.get("folderClass.notFound");
        }
        return messages.get("folderClass.invalid");
    }

    /** The posted template rows, in order; a row whose class is not a number is a row without a class. */
    private static List<TemplateFolder> template(List<String> names, List<String> displayNames, List<String> classIds) {
        List<TemplateFolder> rows = new ArrayList<>();
        int count = names == null ? 0 : names.size();
        for (int i = 0; i < count; i++) {
            String displayName = displayNames != null && i < displayNames.size() ? displayNames.get(i) : null;
            String classId = classIds != null && i < classIds.size() ? classIds.get(i) : null;
            rows.add(new TemplateFolder(names.get(i), displayName, parse(classId)));
        }
        return rows;
    }

    private static Integer parse(String value) {
        try {
            return value == null || value.isBlank() ? null : Integer.valueOf(value.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Form emptyForm() {
        return new Form(null, "", "", false, "NONE", null, withSpareRows(List.of()));
    }

    private static Form formOf(Integer id, Definition definition) {
        return new Form(id, definition.name(), definition.title(), definition.containerOnly(),
                definition.otherChildren() == null ? "NONE" : definition.otherChildren(), definition.otherChildrenClassId(),
                withSpareRows(definition.template().stream().filter(t -> t.name() != null && !t.name().isBlank()).toList()));
    }

    private static List<TemplateFolder> withSpareRows(List<TemplateFolder> rows) {
        List<TemplateFolder> all = new ArrayList<>(rows);
        for (int i = 0; i < SPARE_ROWS; i++) {
            all.add(new TemplateFolder("", "", null));
        }
        return all;
    }

    private void fill(Model model, Form form) {
        model.addAttribute("form", form);
        model.addAttribute("classes", folderClassService.all());
    }
}
