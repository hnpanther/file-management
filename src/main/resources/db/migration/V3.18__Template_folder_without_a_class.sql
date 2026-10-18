-- Roadmap Phase 14, a follow-up met while setting the classes up (2.17.1): a folder of a class's template may be made
-- without a class - a folder for order alone (a contract's Misc), reached only through the grants above it. Until now
-- each named a class, so such a folder needed an empty class of its own. Nothing is changed: every row has a class.

ALTER TABLE folder_class_child ALTER COLUMN child_class_id DROP NOT NULL;
