# ADR-0005: Two-Tier Scanned PDF Strategy (Local Region VLM + Async Desktop/MinerU OCR)

- **Status**: accepted

## Context

Bundling an offline local OCR engine (such as ML Kit Chinese models) inside the APK inflates binary size, drains battery, and performs poorly on two-column academic papers, vertical classical texts, and mathematical formulas. Meanwhile, users already configure multimodal BYOK LLMs and desktop/cloud MinerU OCR pipelines.

## Decision

Do not bundle any local OCR engine in the APK. Ad-hoc questions on a scanned page crop the user-selected bounding region as an image and send it directly to a vision-capable BYOK model (VLM). Full-book companion analysis pushes an asynchronous OCR task to the desktop `book-corpus` / MinerU pipeline, then ingests the cleaned text and block coordinates back into the same Room `L1` tables to unlock the full companion toolbox.

## Consequences

Keeps the APK lean and preserves high accuracy on formulas and classical layouts; the trade-off is that scanned PDFs do not support pure-text selection highlights before full-book OCR completes and depend on network access for VLM or MinerU.
