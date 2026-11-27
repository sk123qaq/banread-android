# Domain Glossary

Pure domain vocabulary for the Android AI companion reader. Every term below defines a business concept and its boundaries without referencing implementation technologies.

## Book & Text Identity

- **Work（作品）**: A logically independent book on the reader's bookshelf that persists across specific file formats or desktop/mobile storage representations.
  _Avoid_: Book entity, file, item
- **Edition File（原书版文件）**: The original e-book distribution file directly imported by the user (such as a specific edition of EPUB, MOBI, AZW3, or PDF).
  _Avoid_: Raw book, source file
- **Source File Hash（原文件指纹）**: An immutable fingerprint uniquely identifying the byte stream of an Edition File or Study Pack, used for deduplication and identity matching.
  _Avoid_: Content hash, checksum
- **Study Pack（预洗伴读包）**: A companion-ready offline book delivery package prepared by the desktop application with cleaned structure, chapter role labels, and optional precomputed companion outputs.
  _Avoid_: Bundle, zip, MD folder
- **Logical Chapter（逻辑章节）**: The smallest ordered chapter unit presented to the reader and AI companion, already normalized by merging short title/front pages and virtually splitting overly long single chapters, identified by a unique 1-based ordinal (`Ordinal`).
  _Avoid_: File, section, part
- **Chapter Content Hash（章节文本指纹）**: A content fingerprint of the source byte streams for a Logical Chapter's body text and footnotes, used to determine whether derived analysis artifacts for that chapter are stale after re-cleaning.
  _Avoid_: Book hash, ETag
- **Chapter Role（章节角色）**: The functional role of a Logical Chapter within the work, strictly partitioned into `Body` (main body), `Notes` (centralized endnote chapter), and `FrontBack` (cover, copyright, prefaces, table of contents, ads, and other peripheral matter).
  _Avoid_: Tag, folder type
- **Semantic Block（语义块）**: The smallest indivisible content unit inside a Logical Chapter carrying a stable sequential identifier (such as `B01`), serving as the common atomic coordinate for reading placement, evidence provenance, and footnote attachment.
  _Avoid_: Paragraph, line, node, chunk
- **Footnote Link（脚注关联）**: An explicit reference from a Semantic Block in a citing body chapter to a target Semantic Block in an in-chapter note or a centralized endnote chapter at the back of the work.
  _Avoid_: Anchor, endnote

## Reading & Spoiler Control

- **Reading Anchor（阅读锚点）**: A coordinate specification pointing accurately to a position or selection within a Logical Chapter across layouts and presentation surfaces (corresponding to a reflowable structural path, a text quote span, or a fixed-page region).
  _Avoid_: Scroll position, offset, pointer
- **Reading Cursor（当前阅读游标）**: The pointer indicating where the reader is currently looking, allowed to move freely backward or forward at any time.
  _Avoid_: Progress, bookmark
- **Spoiler Watermark（防剧透水位线）**: The farthest position boundary actually unlocked by the reader through normal reading; monotonic by default and never regresses when flipping back to earlier chapters.
  _Avoid_: High-water mark, read progress
- **Spoiler Gate（防剧透门控）**: The visibility constraint applied to a companion request—either locked to the current `Spoiler Watermark` or explicitly unlocked by the reader to full-book scope.
  _Avoid_: Filter, lock
- **Annotation（读者批注）**: A bookmark, color highlight, or personal note left by the reader on a specific `Reading Anchor`; treated as high-value personal data that is never evicted by cache cleanup.
  _Avoid_: Pin, assist note

## Companion Intelligence & Sync

- **Discipline Subject（学科分类）**: A two-level manual or automated catalog classification describing the academic or genre domain to which a Work belongs.
  _Avoid_: Genre, category
- **Reading Profile（伴读视角）**: One of the 6+1 reading strategy categories (optionally paired with a literature sub-kind) that determines how to read the Work and which tools are prioritized in the toolbox.
  _Avoid_: Mode, preset, type
- **Companion Tool（伴读工具）**: An analytical command targeting a selected passage, a Logical Chapter, or the scope allowed by a Spoiler Gate, categorized into structured Blueprint diagrams, profile-specific Extension dossiers, general instant actions, and chapter knowledge maps.
  _Avoid_: Plugin, action, feature
- **Evidence Reference（原文证据锚）**: A real `Semantic Block` identifier and provenance tag bound to each conclusion or diagram element produced by AI, strictly distinguishing original work text from model supplementation.
  _Avoid_: Citation, source tag
- **Audited Artifact（已审伴读产物）**: A companion result that has passed structural repair, valid block-ID verification, and credibility filtering, ready for display as a diagram, dossier card, checklist/glossary, or fallback text card.
  _Avoid_: LLM response, output
- **Outbox Ticket（待拉取分发单）**: A delivery task staged on the desktop for a specific mobile device covering one or more Study Packs awaiting pull and acknowledgment.
  _Avoid_: Push job, notification
- **Device State Shard（设备状态分片）**: A lightweight per-device record of Reading Cursor, Spoiler Watermark, and Annotation changes for a Work, written exclusively by one device and merged across devices on read without mutual overwrite.
  _Avoid_: Sync file, snapshot
- **Cross-Book Hit（跨书检索命中）**: A strongly relevant passage and exact locator found by the desktop search engine outside the current Work, used exclusively by cross-book comparison companion tools.
  _Avoid_: Search result
