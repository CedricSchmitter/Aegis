# ADR-0017: Members are persons or the assistant account; "both" is no subject

Status: accepted and implemented for issue #83 (findings F1 and F2). Date: 2026-09-25. Changes the subject values
of [ADR-0002](ADR-0002-document-tree-and-labels.md) and adds a documents port backed by the identity contract.

## Context

The walkthrough of issue #83 found two problems in what a document is about ("Betrifft"):

- **F1.** Besides the house and each member, the subject picker offered `HOUSEHOLD` ("Beide" / "Both"). Upload and
  share ticked it by default, so almost every document carried it, often next to a person, and the subject filter
  for Lisa did not find the documents marked "Beide".
- **F2.** The technical account `aegis-dev` appeared as a member in the subject picker, the subject filter and the
  person list the classifier receives, although no document is about it.

The owner decided on 2026-09-25: remove `HOUSEHOLD` and migrate every stored value to the individual persons ("Beide"
meant Lisa and Cedric, so not the technical account); keep `aegis-dev` as a member shown as `Aegis` that keeps
receiving every notice and stays selectable as responsible for a task, as a base for delegating tasks to an
assistant later; but never offer it as a document subject, in the subject filter or to the classifier.

## Decision

1. **Member kind in identity.** Every member is a `PERSON` or the household's `ASSISTANT` account. The kind is
   persisted on the member (`members.kind`, default `PERSON` for every existing member) and exposed on the public
   `MemberSummary` and on `MemberDto`, so REST, the session and exports carry it. The login username stays as it
   is. Both kinds log in, read the whole household, receive notices and can own tasks; nothing about access
   changes.
2. **The assistant is environment data.** Which account is the assistant is configured per installation in
   `aegis.members.assistants` (`AEGIS_ASSISTANT_MEMBERS`, empty by default), never by a username in a migration. At
   startup, after member provisioning, the identity use case `DesignateAssistants` marks each listed account once
   and renames it to `aegis.members.assistant-display-name` (`Aegis`) through the aggregate. It is idempotent: an
   account that already is the assistant keeps its kind and its current name, and removing a username from the
   setting does not turn the account back into a person. Restore and the members provisioning file create accounts
   with the kind they are given (default person).
3. **Subjects name persons.** `SubjectKind` is `MEMBER` or `HOUSE`. A document about both persons carries one
   `MEMBER` subject per person, so a subject search for one person finds everything that concerns them. The
   documents context learns the persons through its own output port `HouseholdPersons`, implemented in the
   composition root with the identity contract `MemberDirectory` (as the named household process
   `document-subjects`, which the directory is open to). Upload, subject changes, folder implications and accepting
   a subject suggestion reject a newly added member that is not an active person (`subject.member.notPerson`); a
   value a record already carries stays until a member removes it. The classifier receives only active persons,
   no longer offers "both", and a member claim for anyone else is dropped. Upload and share start with no subject
   ticked; the subject filter does not offer the assistant.
4. **One-time startup migration in the documents context.** After provisioning and designation, the documents use
   case `MigrateHouseholdSubjects` replaces every stored `HOUSEHOLD` value by one member value per active person, in
   one transaction, through the documents persistence adapter:
   - `document_subjects`, `category_implied_subjects` and the derived `category_subject_effects` get one `MEMBER`
     row per person (`ON CONFLICT DO NOTHING` keeps an existing one), then the `HOUSEHOLD` rows are deleted;
   - each `HOUSEHOLD` subject suggestion in `document_analyses.subjects` becomes the same suggestion (confidence,
     quote, decision, who decided and when) for each person, in its place. A suggestion that already names the
     person wins, and a pending one is not repeated for a person the document already names. Pending suggestions
     are therefore migrated, not dropped; their review card stays as it is.
   Record versions are not changed, so open review cards keep their fingerprint and no notice is sent again. The
   migration runs at every startup and changes nothing once no `HOUSEHOLD` value is left. Without any active person
   it stops the startup rather than drop what the documents are about.
5. **Why an application migration and not SQL.** A Flyway script would have to read the identity context's
   `members` table to know the persons, which [FOUNDATIONS](../FOUNDATIONS.md) rules out for another context, and
   it would run before the assistant is designated, so it could not tell Lisa and Cedric from `aegis-dev`. The
   startup use case gets the persons through the public identity contract and touches only tables of the documents
   context. The `CHECK` constraints of the subject tables still accept `HOUSEHOLD` so that the migration can read
   old rows; the application never writes it again.
6. **Portability.** Export schema 9 adds the member kind to `data/members.json` and no longer contains
   `HOUSEHOLD`. A restore of an older export maps `HOUSEHOLD` on documents, folder implications and analysis
   suggestions exactly like the startup migration, using the persons of the restoring household; members of an
   older export restore as persons unless the installation designates them. The restore drill passes the exported
   kind and the installation's `AEGIS_ASSISTANT_MEMBERS` to its isolated stack.

## Consequences

- Deploying this change needs the assistant configured **before** the first start of the new version, so that the
  migration adds only the persons (see [DEPLOYMENT.md](../../operations/DEPLOYMENT.md#assistant-account)). Starting
  without the setting treats `aegis-dev` as a person once: the migration would add it to every former "both"
  document, and it would have to be removed by hand.
- A document that already names the assistant (ticked before this change) keeps that subject until a member
  removes it on the document page; the facet filter hides the value.
- A rejected "both" suggestion becomes rejected suggestions for each person on that document, so a later analysis
  of that document does not propose them again.
- Delegating tasks to the assistant is not implemented; the kind only makes that possible without mixing the
  account into the household's persons.
