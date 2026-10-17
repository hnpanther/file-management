package com.hnp.filemanagement.support;

import com.hnp.filemanagement.folder.domain.FolderClassAssignment;
import com.hnp.filemanagement.folder.domain.FolderClassService;
import com.hnp.filemanagement.folder.domain.FolderClassService.Definition;
import com.hnp.filemanagement.folder.domain.FolderClassService.TemplateFolder;
import com.hnp.filemanagement.folder.domain.FolderKind;
import com.hnp.filemanagement.folder.domain.FolderService;
import com.hnp.filemanagement.folder.persistence.FolderRepository;

import java.util.List;

/**
 * The classes agreed for the ERP's contracts (roadmap Phase 14), and a tree of them - what the folder class
 * tests build on:
 *
 * <pre>
 * ERP{n}/                         (no class)
 *   PartyCompany/                 companies     container; any child  -> company
 *     A1/                         company       container; Contract   -> contracts; other children NONE
 *       Contract/                 contracts     container; any child  -> contract
 *         C1/                     contract      container; Documents -> documents, ContractState -> statements; REFUSE
 *           Documents/            documents
 *           ContractState/        statements    container; any child  -> statement
 *       Letters/                  (no class: a company's other folder)
 * </pre>
 */
public final class ClassFixture {

    private ClassFixture() {
    }

    /** The classes' ids. */
    public record Classes(int companies, int company, int contracts, int contract, int documents, int statements,
                          int statement) {
    }

    /** The classes, made bottom up, each name made unique with {@code n}. */
    public static Classes classes(FolderClassService classes, int adminId) {
        int n = TestData.nextSequence();
        int documents = classes.create(definition("documents" + n, "اسناد قرارداد", false, "NONE", null, List.of()), adminId);
        int statement = classes.create(definition("statement" + n, "صورت وضعیت", false, "NONE", null, List.of()), adminId);
        int statements = classes.create(definition("statements" + n, "ظرف صورت وضعیت‌ها", true, "CLASS", statement, List.of()), adminId);
        int contract = classes.create(definition("contract" + n, "قرارداد", true, "REFUSE", null, List.of(
                new TemplateFolder("Documents", "اسناد قرارداد", documents),
                new TemplateFolder("ContractState", "صورت وضعیت‌ها", statements))), adminId);
        int contracts = classes.create(definition("contracts" + n, "ظرف قراردادها", true, "CLASS", contract, List.of()), adminId);
        int company = classes.create(definition("company" + n, "شرکت", true, "NONE", null, List.of(
                new TemplateFolder("Contract", "قراردادها", contracts))), adminId);
        int companies = classes.create(definition("companies" + n, "ظرف شرکت‌ها", true, "CLASS", company, List.of()), adminId);
        return new Classes(companies, company, contracts, contract, documents, statements, statement);
    }

    public static Definition definition(String name, String title, boolean containerOnly, String otherChildren,
                                        Integer otherClassId, List<TemplateFolder> template) {
        return new Definition(name, title, containerOnly, otherChildren, otherClassId, template);
    }

    /** {@code ERP{n}/PartyCompany} under the root, the latter given the companies class: its id, and the bucket's name. */
    public record Root(int bucketId, String bucket, int partyCompanyId) {

        public String path() {
            return bucket + "/PartyCompany";
        }
    }

    public static Root root(FolderService folders, FolderRepository repository, FolderClassAssignment assignment,
                            Classes classes, int adminId) {
        int n = TestData.nextSequence();
        String bucket = "ERP" + n;
        int rootId = repository.findFirstByKind(FolderKind.ROOT).orElseThrow().getId();
        int bucketId = folders.create(rootId, bucket, bucket, null, "erp" + n, adminId).id();
        int partyCompany = folders.create(bucketId, "PartyCompany", "PartyCompany", null, null, adminId).id();
        assignment.set(partyCompany, classes.companies(), adminId);
        return new Root(bucketId, bucket, partyCompany);
    }

    /** A company and its contracts, each made as n8n's upload would make it: by name, under its parent. */
    public record Company(int id, int contractsId, List<Contract> contracts) {
    }

    public record Contract(int id, int documentsId, int contractStateId) {
    }

    public static Company company(FolderService folders, FolderRepository repository, Root root, String name,
                                  int adminId, String... contractNames) {
        int company = folders.create(root.partyCompanyId(), name, name, null, null, adminId).id();
        int contracts = repository.findByParentIdAndNameIgnoreCase(company, "Contract").orElseThrow().getId();
        List<Contract> made = java.util.Arrays.stream(contractNames).map(contractName -> {
            int contract = folders.create(contracts, contractName, contractName, null, null, adminId).id();
            return new Contract(contract,
                    repository.findByParentIdAndNameIgnoreCase(contract, "Documents").orElseThrow().getId(),
                    repository.findByParentIdAndNameIgnoreCase(contract, "ContractState").orElseThrow().getId());
        }).toList();
        return new Company(company, contracts, made);
    }
}
