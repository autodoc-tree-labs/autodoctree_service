import { Node, mergeAttributes } from "@tiptap/core";
import Placeholder from "@tiptap/extension-placeholder";
import Image from "@tiptap/extension-image";
import { Table } from "@tiptap/extension-table";
import TableCell from "@tiptap/extension-table-cell";
import TableHeader from "@tiptap/extension-table-header";
import TableRow from "@tiptap/extension-table-row";
import TaskItem from "@tiptap/extension-task-item";
import TaskList from "@tiptap/extension-task-list";
import { DragHandle } from "@tiptap/extension-drag-handle-react";
import StarterKit from "@tiptap/starter-kit";
import { EditorContent, type Editor, useEditor } from "@tiptap/react";
import { ChevronDown, ChevronRight, GripVertical, ImagePlus, List, ListChecks, ListOrdered, Quote, Table2, Upload } from "lucide-react";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { AttachmentSummary } from "../types";
import { blockDocToMarkdown, normalizeBlockDoc, type BlockDoc } from "./blockContent";
import { buildRecommendedItems, filterSlashItems, type SlashMenuItem } from "./slashMenu";

type UploadAttachmentResult = {
  attachment_id: string;
  filename: string;
  content_type: string;
  size: number;
  download_url?: string | null;
};

type UploadAttachmentFn = (file: File, onProgress: (percent: number) => void) => Promise<UploadAttachmentResult>;
type UploadKind = "image" | "file";

type EditorV2Props = {
  docId: string;
  value: BlockDoc;
  attachments: AttachmentSummary[];
  disabled?: boolean;
  onChange: (next: BlockDoc) => void;
  onUploadAttachment: UploadAttachmentFn;
};

type SlashItem = SlashMenuItem & { run: (editor: Editor) => void };

type SlashState = {
  open: boolean;
  from: number;
  to: number;
  query: string;
  x: number;
  y: number;
};

type EmojiPickerState = {
  open: boolean;
  x: number;
  y: number;
};

const EMPTY_SLASH_STATE: SlashState = {
  open: false,
  from: 0,
  to: 0,
  query: "",
  x: 0,
  y: 0
};

const EMPTY_EMOJI_PICKER_STATE: EmojiPickerState = {
  open: false,
  x: 0,
  y: 0
};

const EMOJI_OPTIONS: Array<{ value: string; label: string }> = [
  { value: "😀", label: "Smile" },
  { value: "😄", label: "Happy" },
  { value: "😉", label: "Wink" },
  { value: "🤔", label: "Think" },
  { value: "👍", label: "Thumbs up" },
  { value: "✅", label: "Check" },
  { value: "🔥", label: "Fire" },
  { value: "🎉", label: "Party" },
  { value: "🚀", label: "Rocket" },
  { value: "💡", label: "Idea" },
  { value: "📌", label: "Pin" },
  { value: "⚠️", label: "Warning" },
  { value: "🧠", label: "Brain" },
  { value: "📎", label: "Clip" },
  { value: "📝", label: "Memo" },
  { value: "🔍", label: "Search" }
];

const CODE_LANGUAGES = ["plaintext", "kotlin", "typescript", "javascript", "python", "java", "sql", "bash"];
const IMAGE_WIDTH_OPTIONS = ["25%", "50%", "75%", "100%"];
const RECENT_SLASH_KEY = "autodoc.editor.v2.recentSlash.v1";
const MIN_IMAGE_WIDTH_PERCENT = 20;
const MAX_IMAGE_WIDTH_PERCENT = 100;
const detectUploadKind = (file: File): UploadKind => (file.type.toLowerCase().startsWith("image/") ? "image" : "file");
const hasDraggedFiles = (dataTransfer: DataTransfer | null | undefined): boolean => {
  if (!dataTransfer) {
    return false;
  }
  const items = dataTransfer.items;
  if (items && items.length > 0) {
    return Array.from(items).some((item) => item.kind === "file");
  }
  return dataTransfer.files.length > 0;
};

const buildUploadInsertContent = (uploaded: UploadAttachmentResult, kind: UploadKind): Record<string, unknown> =>
  kind === "image"
    ? {
        type: "image",
        attrs: {
          src: uploaded.download_url || "",
          alt: uploaded.filename,
          attachmentId: uploaded.attachment_id,
          filename: uploaded.filename,
          mimeType: uploaded.content_type,
          size: uploaded.size,
          width: "100%"
        }
      }
    : {
        type: "fileBlock",
        attrs: {
          attachmentId: uploaded.attachment_id,
          filename: uploaded.filename,
          mimeType: uploaded.content_type,
          size: uploaded.size,
          url: uploaded.download_url || ""
        }
      };

const clampImageWidthPercent = (value: number): number => Math.max(MIN_IMAGE_WIDTH_PERCENT, Math.min(MAX_IMAGE_WIDTH_PERCENT, value));

const normalizeImageWidth = (rawWidth: unknown): string => {
  if (typeof rawWidth === "string") {
    const trimmed = rawWidth.trim();
    if (trimmed.endsWith("%")) {
      const parsed = Number.parseFloat(trimmed.slice(0, -1));
      if (Number.isFinite(parsed)) {
        return `${Math.round(clampImageWidthPercent(parsed))}%`;
      }
    }
    const parsed = Number.parseFloat(trimmed);
    if (Number.isFinite(parsed)) {
      return `${Math.round(clampImageWidthPercent(parsed))}%`;
    }
  }
  return "100%";
};

const toImageWidthPercent = (rawWidth: unknown, containerWidthPx: number): number => {
  if (typeof rawWidth !== "string") {
    return MAX_IMAGE_WIDTH_PERCENT;
  }
  const trimmed = rawWidth.trim();
  if (!trimmed) {
    return MAX_IMAGE_WIDTH_PERCENT;
  }
  if (trimmed.endsWith("%")) {
    const parsed = Number.parseFloat(trimmed.slice(0, -1));
    return Number.isFinite(parsed) ? clampImageWidthPercent(parsed) : MAX_IMAGE_WIDTH_PERCENT;
  }
  if (trimmed.endsWith("px")) {
    const parsed = Number.parseFloat(trimmed.slice(0, -2));
    if (Number.isFinite(parsed) && containerWidthPx > 0) {
      return clampImageWidthPercent((parsed / containerWidthPx) * 100);
    }
    return MAX_IMAGE_WIDTH_PERCENT;
  }
  const parsed = Number.parseFloat(trimmed);
  return Number.isFinite(parsed) ? clampImageWidthPercent(parsed) : MAX_IMAGE_WIDTH_PERCENT;
};

const ToggleBlock = Node.create({
  name: "toggleBlock",
  group: "block",
  content: "block+",
  defining: true,
  draggable: true,
  addAttributes() {
    return {
      title: {
        default: "토글"
      },
      collapsed: {
        default: false
      }
    };
  },
  parseHTML() {
    return [{ tag: 'div[data-type="toggle-block"]' }];
  },
  renderHTML({ HTMLAttributes }) {
    const attrs = HTMLAttributes as Record<string, unknown>;
    const title = typeof attrs.title === "string" ? attrs.title : "토글";
    const collapsed = Boolean(attrs.collapsed);
    return [
      "div",
      mergeAttributes(HTMLAttributes, {
        "data-type": "toggle-block",
        "data-collapsed": collapsed ? "true" : "false"
      }),
      ["div", { class: "editor-v2-toggle-label" }, title],
      ["div", { class: "editor-v2-toggle-content" }, 0]
    ];
  },
  addNodeView() {
    return ({ node, getPos, editor }) => {
      let currentNode = node;
      const dom = document.createElement("div");
      dom.dataset.type = "toggle-block";
      dom.className = "editor-v2-toggle-node";

      const headerButton = document.createElement("button");
      headerButton.type = "button";
      headerButton.className = "editor-v2-toggle-header";

      const arrow = document.createElement("span");
      arrow.className = "editor-v2-toggle-arrow";
      const title = document.createElement("span");
      title.className = "editor-v2-toggle-title";

      const contentDOM = document.createElement("div");
      contentDOM.className = "editor-v2-toggle-content";

      headerButton.append(arrow, title);
      dom.append(headerButton, contentDOM);

      const sync = () => {
        const collapsed = Boolean(currentNode.attrs.collapsed);
        const labelText = typeof currentNode.attrs.title === "string" ? currentNode.attrs.title : "토글";
        dom.dataset.collapsed = collapsed ? "true" : "false";
        arrow.textContent = collapsed ? "▶" : "▼";
        title.textContent = labelText;
      };

      headerButton.addEventListener("click", (event) => {
        event.preventDefault();
        const pos = getPos();
        if (typeof pos !== "number") {
          return;
        }
        const nextAttrs = {
          ...currentNode.attrs,
          collapsed: !currentNode.attrs.collapsed
        };
        editor.view.dispatch(editor.state.tr.setNodeMarkup(pos, undefined, nextAttrs));
      });

      sync();

      return {
        dom,
        contentDOM,
        update(updatedNode) {
          currentNode = updatedNode;
          sync();
          return true;
        }
      };
    };
  }
});

const Callout = Node.create({
  name: "callout",
  group: "block",
  content: "block+",
  defining: true,
  draggable: true,
  addAttributes() {
    return {
      icon: {
        default: "💡"
      },
      tone: {
        default: "default"
      }
    };
  },
  parseHTML() {
    return [{ tag: 'div[data-type="callout"]' }];
  },
  renderHTML({ HTMLAttributes }) {
    const attrs = HTMLAttributes as Record<string, unknown>;
    const icon = typeof attrs.icon === "string" ? attrs.icon : "💡";
    const tone = typeof attrs.tone === "string" ? attrs.tone : "default";
    return [
      "div",
      mergeAttributes(HTMLAttributes, {
        "data-type": "callout",
        "data-tone": tone,
        class: "editor-v2-callout"
      }),
      ["div", { class: "editor-v2-callout-icon" }, icon],
      ["div", { class: "editor-v2-callout-body" }, 0]
    ];
  }
});

const FileBlock = Node.create({
  name: "fileBlock",
  group: "block",
  atom: true,
  selectable: true,
  draggable: true,
  addAttributes() {
    return {
      attachmentId: { default: null },
      filename: { default: "file" },
      mimeType: { default: "application/octet-stream" },
      size: { default: 0 },
      url: { default: null }
    };
  },
  parseHTML() {
    return [{ tag: 'div[data-type="file-block"]' }];
  },
  renderHTML({ HTMLAttributes }) {
    const attrs = HTMLAttributes as Record<string, unknown>;
    const filename = typeof attrs.filename === "string" ? attrs.filename : "file";
    const url = typeof attrs.url === "string" ? attrs.url : "";
    const href = url || "#";

    return [
      "div",
      mergeAttributes(HTMLAttributes, {
        "data-type": "file-block",
        class: "editor-v2-file"
      }),
      ["a", { href, target: "_blank", rel: "noreferrer" }, filename]
    ];
  }
});

const TocBlock = Node.create({
  name: "tocBlock",
  group: "block",
  atom: true,
  selectable: true,
  draggable: true,
  parseHTML() {
    return [{ tag: 'div[data-type="toc-block"]' }];
  },
  renderHTML({ HTMLAttributes }) {
    return [
      "div",
      mergeAttributes(HTMLAttributes, {
        "data-type": "toc-block",
        class: "editor-v2-toc"
      })
    ];
  },
  addNodeView() {
    return ({ editor }) => {
      const dom = document.createElement("div");
      dom.className = "editor-v2-toc";
      dom.dataset.type = "toc-block";

      const title = document.createElement("p");
      title.className = "editor-v2-toc-title";
      title.textContent = "목차";

      const list = document.createElement("div");
      list.className = "editor-v2-toc-list";

      dom.append(title, list);

      const renderToc = () => {
        const headings: Array<{ text: string; level: number; pos: number }> = [];
        editor.state.doc.descendants((node, pos) => {
          if (node.type.name === "heading") {
            headings.push({
              text: node.textContent || "제목",
              level: Math.max(1, Math.min(3, Number(node.attrs.level ?? 1))),
              pos
            });
          }
        });

        list.innerHTML = "";

        if (headings.length === 0) {
          const empty = document.createElement("p");
          empty.className = "editor-v2-toc-empty";
          empty.textContent = "헤딩을 추가하면 목차가 자동 생성됩니다.";
          list.appendChild(empty);
          return;
        }

        headings.forEach((heading) => {
          const button = document.createElement("button");
          button.type = "button";
          button.className = `editor-v2-toc-item level-${heading.level}`;
          button.textContent = heading.text;
          button.addEventListener("click", (event) => {
            event.preventDefault();
            editor.commands.focus();
            editor.commands.setTextSelection(heading.pos + 1);
          });
          list.appendChild(button);
        });
      };

      renderToc();
      const rerender = () => renderToc();
      editor.on("update", rerender);

      return {
        dom,
        destroy() {
          editor.off("update", rerender);
        }
      };
    };
  }
});

const ExtendedImage = Image.extend({
  addAttributes() {
    return {
      ...(this.parent?.() ?? {}),
      attachmentId: {
        default: null
      },
      filename: {
        default: null
      },
      mimeType: {
        default: null
      },
      size: {
        default: null
      },
      width: {
        default: "100%",
        parseHTML: (element) => {
          const dataWidth = element.getAttribute("data-width");
          if (dataWidth) {
            return dataWidth;
          }
          const styleWidth = element.getAttribute("style") ?? "";
          const match = styleWidth.match(/width:\s*([^;]+)/i);
          return match?.[1]?.trim() || "100%";
        },
        renderHTML: (attrs) => {
          const width = typeof attrs.width === "string" && attrs.width.trim() ? attrs.width.trim() : "100%";
          return {
            "data-width": width,
            style: `width: ${width};`
          };
        }
      }
    };
  },
  addNodeView() {
    return ({ node, editor, getPos }) => {
      let currentNode = node;
      let isDragging = false;
      let detachDragEvents: (() => void) | null = null;

      const dom = document.createElement("div");
      dom.className = "editor-v2-image-node";
      dom.dataset.type = "image-block";

      const image = document.createElement("img");
      image.className = "editor-v2-image-el";
      image.draggable = false;

      const resizeHandle = document.createElement("button");
      resizeHandle.type = "button";
      resizeHandle.className = "editor-v2-image-resize-handle";
      resizeHandle.setAttribute("aria-label", "Resize image");
      resizeHandle.tabIndex = -1;

      dom.append(image, resizeHandle);

      const editorWidthPx = () => {
        try {
          const width = editor.view.dom.getBoundingClientRect().width;
          if (Number.isFinite(width) && width > 0) {
            return width;
          }
        } catch {
          // TipTap node view can be created before editor.view is fully mounted.
        }
        const fallback = dom.parentElement?.getBoundingClientRect().width;
        if (typeof fallback === "number" && Number.isFinite(fallback) && fallback > 0) {
          return fallback;
        }
        return 960;
      };
      const getNodePos = (): number | null => {
        try {
          const pos = getPos();
          return typeof pos === "number" ? pos : null;
        } catch {
          return null;
        }
      };

      const applyNodeAttrs = () => {
        const attrs = (currentNode.attrs ?? {}) as Record<string, unknown>;
        const src = typeof attrs.src === "string" ? attrs.src : "";
        const alt = typeof attrs.alt === "string" ? attrs.alt : "";
        const widthPercent = toImageWidthPercent(attrs.width, editorWidthPx());
        image.setAttribute("src", src);
        image.setAttribute("alt", alt);
        dom.style.width = `${Math.round(widthPercent)}%`;
      };

      const selectCurrentImageNode = () => {
        const pos = getNodePos();
        if (typeof pos !== "number") {
          return;
        }
        editor.chain().focus().setNodeSelection(pos).run();
      };

      const onResizeStart = (event: MouseEvent) => {
        if (!editor.isEditable) {
          return;
        }
        event.preventDefault();
        event.stopPropagation();
        selectCurrentImageNode();

        const startX = event.clientX;
        const startWidthPx = dom.getBoundingClientRect().width;
        const availableWidthPx = editorWidthPx();
        const minWidthPx = Math.max(120, availableWidthPx * (MIN_IMAGE_WIDTH_PERCENT / 100));
        const maxWidthPx = Math.max(minWidthPx, availableWidthPx * (MAX_IMAGE_WIDTH_PERCENT / 100));
        let nextWidthPercent = clampImageWidthPercent((startWidthPx / availableWidthPx) * 100);
        isDragging = true;
        dom.classList.add("is-resizing");

        const onResizeMove = (moveEvent: MouseEvent) => {
          const deltaX = moveEvent.clientX - startX;
          const nextWidthPx = Math.max(minWidthPx, Math.min(maxWidthPx, startWidthPx + deltaX));
          nextWidthPercent = clampImageWidthPercent((nextWidthPx / availableWidthPx) * 100);
          dom.style.width = `${Math.round(nextWidthPercent)}%`;
        };

        const onResizeEnd = () => {
          detachDragEvents?.();
          detachDragEvents = null;
          isDragging = false;
          dom.classList.remove("is-resizing");

          const pos = getNodePos();
          if (typeof pos !== "number") {
            return;
          }
          const width = `${Math.round(nextWidthPercent)}%`;
          if (currentNode.attrs.width === width) {
            return;
          }
          const nextAttrs = {
            ...currentNode.attrs,
            width
          };
          editor.view.dispatch(editor.state.tr.setNodeMarkup(pos, undefined, nextAttrs));
        };

        window.addEventListener("mousemove", onResizeMove);
        window.addEventListener("mouseup", onResizeEnd, { once: true });
        detachDragEvents = () => {
          window.removeEventListener("mousemove", onResizeMove);
          window.removeEventListener("mouseup", onResizeEnd);
        };
      };

      const onImageMouseDown = (event: MouseEvent) => {
        if (event.button !== 0) {
          return;
        }
        selectCurrentImageNode();
      };

      resizeHandle.addEventListener("mousedown", onResizeStart);
      image.addEventListener("mousedown", onImageMouseDown);

      applyNodeAttrs();

      return {
        dom,
        update(updatedNode) {
          if (updatedNode.type.name !== "image") {
            return false;
          }
          currentNode = updatedNode;
          if (!isDragging) {
            applyNodeAttrs();
          }
          return true;
        },
        destroy() {
          detachDragEvents?.();
          resizeHandle.removeEventListener("mousedown", onResizeStart);
          image.removeEventListener("mousedown", onImageMouseDown);
        }
      };
    };
  }
});

const hydrateDocumentWithAttachments = (doc: BlockDoc, attachments: AttachmentSummary[]): BlockDoc => {
  const byId = new Map(attachments.map((attachment) => [attachment.id, attachment]));

  const mapNode = (node: Record<string, unknown>): Record<string, unknown> => {
    const content = Array.isArray(node.content) ? node.content.map((child) => mapNode(child as Record<string, unknown>)) : undefined;
    const attrs = typeof node.attrs === "object" && node.attrs ? ({ ...(node.attrs as Record<string, unknown>) }) : undefined;

    if (attrs && typeof attrs.attachmentId === "string") {
      const matched = byId.get(attrs.attachmentId);
      if (matched) {
        if (!attrs.filename && matched.filename) {
          attrs.filename = matched.filename;
        }
        if (!attrs.mimeType && matched.content_type) {
          attrs.mimeType = matched.content_type;
        }
        if (!attrs.size && matched.size) {
          attrs.size = matched.size;
        }
        if (matched.download_url) {
          attrs.url = matched.download_url;
          attrs.src = matched.download_url;
        }
      }
    }

    if (node.type === "image") {
      const nextAttrs = attrs ?? {};
      nextAttrs.width = normalizeImageWidth(nextAttrs.width);
      return {
        ...node,
        attrs: nextAttrs,
        ...(content ? { content } : {})
      };
    }

    return {
      ...node,
      ...(attrs ? { attrs } : {}),
      ...(content ? { content } : {})
    };
  };

  return {
    type: "doc",
    content: doc.content.map((node) => mapNode(node as Record<string, unknown>) as never)
  };
};

export function EditorV2({ docId, value, attachments, disabled = false, onChange, onUploadAttachment }: EditorV2Props) {
  const [slash, setSlash] = useState<SlashState>(EMPTY_SLASH_STATE);
  const [slashIndex, setSlashIndex] = useState(0);
  const [emojiPicker, setEmojiPicker] = useState<EmojiPickerState>(EMPTY_EMOJI_PICKER_STATE);
  const [recentSlashIds, setRecentSlashIds] = useState<string[]>([]);
  const [uploadProgress, setUploadProgress] = useState<number | null>(null);
  const [uploadError, setUploadError] = useState<string | null>(null);
  const [isDragOver, setIsDragOver] = useState(false);
  const [codeLanguage, setCodeLanguage] = useState("plaintext");
  const [selectedImageWidth, setSelectedImageWidth] = useState<string | null>(null);
  const imageInputRef = useRef<HTMLInputElement | null>(null);
  const fileInputRef = useRef<HTMLInputElement | null>(null);
  const editorSurfaceRef = useRef<HTMLDivElement | null>(null);
  const slashMenuRef = useRef<HTMLDivElement | null>(null);
  const emojiPickerRef = useRef<HTMLDivElement | null>(null);
  const slashItemRefs = useRef<Array<HTMLButtonElement | null>>([]);
  const pendingUploadKindRef = useRef<"image" | "file" | null>(null);
  const appliedContentRef = useRef("");
  const slashRef = useRef<SlashState>(EMPTY_SLASH_STATE);
  const isComposingRef = useRef(false);

  const hydratedDoc = useMemo(() => hydrateDocumentWithAttachments(value, attachments), [attachments, value]);

  const editor = useEditor({
    extensions: [
      StarterKit.configure({
        heading: {
          levels: [1, 2, 3]
        }
      }),
      Placeholder.configure({
        placeholder: "여기에 내용을 입력하세요. '/'를 입력하면 블록 메뉴가 열립니다."
      }),
      TaskList,
      TaskItem.configure({ nested: true }),
      Table.configure({
        resizable: true
      }),
      TableRow,
      TableHeader,
      TableCell,
      ExtendedImage.configure({
        allowBase64: true,
        inline: false
      }),
      ToggleBlock,
      Callout,
      FileBlock,
      TocBlock
    ],
    editorProps: {
      attributes: {
        class: "editor-v2-prosemirror"
      }
    },
    content: hydratedDoc,
    editable: !disabled,
    onUpdate: ({ editor: current }) => {
      const next = current.getJSON() as BlockDoc;
      const serialized = JSON.stringify(next);
      appliedContentRef.current = serialized;
      onChange(next);
    }
  });

  useEffect(() => {
    slashRef.current = slash;
  }, [slash]);

  useEffect(() => {
    try {
      const raw = window.localStorage.getItem(RECENT_SLASH_KEY);
      if (!raw) {
        return;
      }
      const parsed = JSON.parse(raw);
      if (Array.isArray(parsed)) {
        setRecentSlashIds(parsed.filter((value): value is string => typeof value === "string" && value.trim().length > 0).slice(0, 10));
      }
    } catch {
      // ignore local storage parse errors
    }
  }, []);

  useEffect(() => {
    if (!editor) {
      return;
    }
    const serialized = JSON.stringify(hydratedDoc);
    if (appliedContentRef.current === serialized) {
      return;
    }
    editor.commands.setContent(hydratedDoc, { emitUpdate: false });
    appliedContentRef.current = serialized;
  }, [editor, hydratedDoc]);

  useEffect(() => {
    if (!editor) {
      return;
    }
    const syncCodeLanguage = () => {
      const codeBlock = editor.state.selection.$from.parent.type.name === "codeBlock" ? editor.state.selection.$from.parent : null;
      const languageAttr = codeBlock?.attrs?.language;
      setCodeLanguage(typeof languageAttr === "string" && languageAttr ? languageAttr : "plaintext");
      const selectedNode = (editor.state.selection as unknown as { node?: { type?: { name?: string }; attrs?: Record<string, unknown> } }).node;
      if (selectedNode?.type?.name === "image") {
        setSelectedImageWidth(normalizeImageWidth(selectedNode.attrs?.width));
      } else {
        setSelectedImageWidth(null);
      }
    };
    syncCodeLanguage();
    editor.on("selectionUpdate", syncCodeLanguage);
    return () => {
      editor.off("selectionUpdate", syncCodeLanguage);
    };
  }, [editor]);

  useEffect(() => {
    if (!editor) {
      return;
    }
    const onCompositionStart = () => {
      isComposingRef.current = true;
    };
    const onCompositionEnd = () => {
      isComposingRef.current = false;
    };
    editor.view.dom.addEventListener("compositionstart", onCompositionStart);
    editor.view.dom.addEventListener("compositionend", onCompositionEnd);
    return () => {
      editor.view.dom.removeEventListener("compositionstart", onCompositionStart);
      editor.view.dom.removeEventListener("compositionend", onCompositionEnd);
    };
  }, [editor]);

  const closeSlashMenu = useCallback(() => {
    setSlash(EMPTY_SLASH_STATE);
    setSlashIndex(0);
  }, []);

  const closeEmojiPicker = useCallback(() => {
    setEmojiPicker(EMPTY_EMOJI_PICKER_STATE);
  }, []);

  const openEmojiPicker = useCallback(() => {
    const margin = 12;
    const pickerWidth = Math.min(300, window.innerWidth - margin * 2);
    const pickerHeight = 220;
    const anchorX = slashRef.current.open ? slashRef.current.x : margin;
    const anchorY = slashRef.current.open ? slashRef.current.y : margin;

    let x = anchorX;
    if (x + pickerWidth > window.innerWidth - margin) {
      x = window.innerWidth - pickerWidth - margin;
    }
    if (x < margin) {
      x = margin;
    }

    let y = anchorY;
    if (y + pickerHeight > window.innerHeight - margin) {
      y = Math.max(margin, anchorY - pickerHeight - 8);
    }

    setEmojiPicker({
      open: true,
      x,
      y
    });
  }, []);

  const insertEmojiAtCursor = useCallback(
    (emoji: string) => {
      if (!editor) {
        return;
      }
      editor.chain().focus().insertContent(`${emoji} `).run();
      closeEmojiPicker();
    },
    [closeEmojiPicker, editor]
  );

  const setSlashPosition = useCallback(
    (targetEditor: Editor, from: number, to: number, query: string) => {
      const safePos = Math.max(1, Math.min(to, targetEditor.state.doc.content.size));
      const coords = targetEditor.view.coordsAtPos(safePos);
      const margin = 12;
      const menuWidth = Math.min(360, window.innerWidth - margin * 2);
      const estimatedHeight = 360;

      let x = coords.left;
      if (x + menuWidth > window.innerWidth - margin) {
        x = window.innerWidth - menuWidth - margin;
      }
      if (x < margin) {
        x = margin;
      }

      let y = coords.bottom + 6;
      if (y + estimatedHeight > window.innerHeight - margin) {
        y = Math.max(margin, coords.top - estimatedHeight - 6);
      }

      setSlash({
        open: true,
        from,
        to,
        query,
        x,
        y
      });
    },
    []
  );

  const deleteSlashRange = useCallback(
    (targetEditor: Editor) => {
      const current = slashRef.current;
      if (!current.open) {
        return;
      }
      targetEditor.chain().focus().deleteRange({ from: current.from, to: current.to }).run();
      closeSlashMenu();
    },
    [closeSlashMenu]
  );

  const insertUploadedAttachment = useCallback(
    (targetEditor: Editor, uploaded: UploadAttachmentResult, kind: UploadKind, insertPos?: number): number | undefined => {
      const content = buildUploadInsertContent(uploaded, kind);
      if (typeof insertPos === "number") {
        const safePos = Math.max(1, Math.min(insertPos, targetEditor.state.doc.content.size));
        const insertedAtPosition = targetEditor.chain().focus().insertContentAt(safePos, content).run();
        if (insertedAtPosition) {
          return targetEditor.state.selection.to;
        }
      }
      targetEditor.chain().focus().insertContent(content).run();
      return undefined;
    },
    []
  );

  const uploadAndInsertEntries = useCallback(
    async (entries: Array<{ file: File; kind: UploadKind }>, options?: { insertPos?: number }) => {
      if (!editor || entries.length === 0) {
        return;
      }

      let nextInsertPos = options?.insertPos;
      setUploadError(null);

      for (const entry of entries) {
        setUploadProgress(0);
        try {
          const uploaded = await onUploadAttachment(entry.file, (percent) => {
            setUploadProgress(percent);
          });
          setUploadProgress(100);
          const insertedSelectionPos = insertUploadedAttachment(editor, uploaded, entry.kind, nextInsertPos);
          nextInsertPos = typeof insertedSelectionPos === "number" ? insertedSelectionPos : undefined;
        } catch (error) {
          const message = error instanceof Error ? error.message : "파일 업로드에 실패했습니다.";
          setUploadError(message);
        } finally {
          setTimeout(() => {
            setUploadProgress(null);
          }, 500);
        }
      }
    },
    [editor, insertUploadedAttachment, onUploadAttachment]
  );

  const handleUploadFiles = useCallback(
    async (files: FileList | null) => {
      if (!files || files.length === 0) {
        pendingUploadKindRef.current = null;
        return;
      }
      const kind = pendingUploadKindRef.current;
      pendingUploadKindRef.current = null;
      if (!kind) {
        return;
      }
      await uploadAndInsertEntries(Array.from(files).map((file) => ({ file, kind })));
    },
    [uploadAndInsertEntries]
  );

  const openUploadDialog = useCallback((kind: "image" | "file") => {
    pendingUploadKindRef.current = kind;
    if (kind === "image") {
      imageInputRef.current?.click();
    } else {
      fileInputRef.current?.click();
    }
  }, []);

  useEffect(() => {
    if (!editor || disabled) {
      setIsDragOver(false);
      return;
    }

    const editorDom = editor.view.dom;
    const surfaceDom = editorSurfaceRef.current ?? editorDom;
    const isFileDragEvent = (event: DragEvent): boolean => hasDraggedFiles(event.dataTransfer);

    const handleDragEnter = (event: DragEvent) => {
      if (!isFileDragEvent(event)) {
        return;
      }
      event.preventDefault();
      event.stopPropagation();
      setIsDragOver(true);
    };

    const handleDragOver = (event: DragEvent) => {
      if (!isFileDragEvent(event)) {
        return;
      }
      event.preventDefault();
      event.stopPropagation();
      if (event.dataTransfer) {
        event.dataTransfer.dropEffect = "copy";
      }
      setIsDragOver(true);
    };

    const handleDragLeave = (event: DragEvent) => {
      const nextTarget = event.relatedTarget;
      if (nextTarget instanceof globalThis.Node && surfaceDom.contains(nextTarget)) {
        return;
      }
      setIsDragOver(false);
    };

    const handleDrop = (event: DragEvent) => {
      if (!isFileDragEvent(event)) {
        return;
      }
      event.preventDefault();
      event.stopPropagation();
      setIsDragOver(false);

      const droppedFiles = Array.from(event.dataTransfer?.files ?? []);
      if (droppedFiles.length === 0) {
        return;
      }

      const rawDropPos = editor.view.posAtCoords({ left: event.clientX, top: event.clientY })?.pos;
      const fallbackPos = editor.state.selection.to;
      const dropPos = typeof rawDropPos === "number" ? Math.max(1, Math.min(rawDropPos, editor.state.doc.content.size)) : fallbackPos;
      void uploadAndInsertEntries(
        droppedFiles.map((file) => ({ file, kind: detectUploadKind(file) })),
        { insertPos: dropPos }
      );
    };

    const preventWindowFileOpen = (event: DragEvent) => {
      if (!isFileDragEvent(event)) {
        return;
      }
      event.preventDefault();
      if (event.dataTransfer) {
        event.dataTransfer.dropEffect = "copy";
      }
    };

    const clearDragHintOnWindowDrop = (event: DragEvent) => {
      if (!isFileDragEvent(event)) {
        return;
      }
      setIsDragOver(false);
    };

    surfaceDom.addEventListener("dragenter", handleDragEnter, true);
    surfaceDom.addEventListener("dragover", handleDragOver, true);
    surfaceDom.addEventListener("dragleave", handleDragLeave, true);
    surfaceDom.addEventListener("drop", handleDrop, true);
    window.addEventListener("dragover", preventWindowFileOpen, true);
    window.addEventListener("drop", preventWindowFileOpen, true);
    window.addEventListener("drop", clearDragHintOnWindowDrop, true);

    return () => {
      setIsDragOver(false);
      surfaceDom.removeEventListener("dragenter", handleDragEnter, true);
      surfaceDom.removeEventListener("dragover", handleDragOver, true);
      surfaceDom.removeEventListener("dragleave", handleDragLeave, true);
      surfaceDom.removeEventListener("drop", handleDrop, true);
      window.removeEventListener("dragover", preventWindowFileOpen, true);
      window.removeEventListener("drop", preventWindowFileOpen, true);
      window.removeEventListener("drop", clearDragHintOnWindowDrop, true);
    };
  }, [disabled, editor, uploadAndInsertEntries]);

  const moveCurrentBlock = useCallback(
    (direction: "up" | "down") => {
      if (!editor) {
        return;
      }
      const state = editor.state;
      const { $from } = state.selection;
      let targetIndex = -1;
      const nodes = [] as Array<ReturnType<typeof state.doc.child>>;

      state.doc.forEach((node, offset) => {
        nodes.push(node);
        if (offset <= $from.pos && $from.pos <= offset + node.nodeSize && targetIndex < 0) {
          targetIndex = nodes.length - 1;
        }
      });

      if (targetIndex < 0) {
        return;
      }
      const swapIndex = direction === "up" ? targetIndex - 1 : targetIndex + 1;
      if (swapIndex < 0 || swapIndex >= nodes.length) {
        return;
      }

      const reordered = [...nodes];
      const temp = reordered[targetIndex];
      reordered[targetIndex] = reordered[swapIndex];
      reordered[swapIndex] = temp;

      const tr = state.tr.replaceWith(0, state.doc.content.size, reordered);
      editor.view.dispatch(tr);
      const newPos = swapIndex <= 0 ? 1 : reordered.slice(0, swapIndex).reduce((acc, node) => acc + node.nodeSize, 1);
      editor.commands.setTextSelection(newPos);
      editor.commands.focus();
    },
    [editor]
  );

  const copyCurrentCodeBlock = useCallback(async () => {
    if (!editor) {
      return;
    }
    const node = editor.state.selection.$from.parent;
    if (node.type.name !== "codeBlock") {
      setUploadError("코드 블록 안에서 복사 버튼을 사용하세요.");
      return;
    }
    const text = node.textContent || "";
    if (!text.trim()) {
      setUploadError("복사할 코드가 없습니다.");
      return;
    }
    try {
      await navigator.clipboard.writeText(text);
      setUploadError(null);
    } catch {
      setUploadError("클립보드 복사에 실패했습니다.");
    }
  }, [editor]);

  const registerRecentSlashItem = useCallback((itemId: string) => {
    setRecentSlashIds((previous) => {
      const deduped = [itemId, ...previous.filter((id) => id !== itemId)].slice(0, 10);
      try {
        window.localStorage.setItem(RECENT_SLASH_KEY, JSON.stringify(deduped));
      } catch {
        // ignore local storage errors
      }
      return deduped;
    });
  }, []);

  const slashItems = useMemo<SlashItem[]>(() => {
    const insertToggle = (targetEditor: Editor) => {
      targetEditor.chain().focus().insertContent({
        type: "toggleBlock",
        attrs: { title: "토글" },
        content: [{ type: "paragraph", content: [{ type: "text", text: "토글 내용을 입력하세요." }] }]
      }).run();
    };

    const insertCallout = (targetEditor: Editor) => {
      targetEditor.chain().focus().insertContent({
        type: "callout",
        attrs: { icon: "💡", tone: "default" },
        content: [{ type: "paragraph", content: [{ type: "text", text: "콜아웃 내용을 입력하세요." }] }]
      }).run();
    };

    return [
      {
        id: "text",
        label: "Text",
        description: "일반 텍스트 블록",
        group: "basic",
        aliases: ["/text", "/t", "/텍스트"],
        keywords: ["paragraph", "text", "본문", "문단", "텍스트"],
        run: (targetEditor) => targetEditor.chain().focus().setParagraph().run()
      },
      {
        id: "emoji",
        label: "Emoji",
        description: "이모지 선택",
        group: "basic",
        aliases: ["/emoji", "/em", "/이모지"],
        keywords: ["emoji", "emoticon", "icon", "smile", "이모지", "표정"],
        run: () => openEmojiPicker()
      },
      {
        id: "h1",
        label: "Heading 1",
        description: "큰 제목",
        group: "basic",
        aliases: ["/h1", "/#"],
        keywords: ["heading", "h1", "title", "제목1", "큰제목"],
        run: (targetEditor) => targetEditor.chain().focus().toggleHeading({ level: 1 }).run()
      },
      {
        id: "h2",
        label: "Heading 2",
        description: "중간 제목",
        group: "basic",
        aliases: ["/h2", "/##"],
        keywords: ["heading", "h2", "제목2", "중간제목"],
        run: (targetEditor) => targetEditor.chain().focus().toggleHeading({ level: 2 }).run()
      },
      {
        id: "h3",
        label: "Heading 3",
        description: "작은 제목",
        group: "basic",
        aliases: ["/h3", "/###"],
        keywords: ["heading", "h3", "제목3", "작은제목"],
        run: (targetEditor) => targetEditor.chain().focus().toggleHeading({ level: 3 }).run()
      },
      {
        id: "bullet",
        label: "Bulleted list",
        description: "불릿 목록",
        group: "basic",
        aliases: ["/bullet", "/ul", "/불릿"],
        keywords: ["bullet", "list", "ul", "불릿", "목록", "리스트"],
        run: (targetEditor) => targetEditor.chain().focus().toggleBulletList().run()
      },
      {
        id: "numbered",
        label: "Numbered list",
        description: "번호 목록",
        group: "basic",
        aliases: ["/numbered", "/ol", "/번호목록"],
        keywords: ["ordered", "list", "ol", "numbered", "번호", "순서목록"],
        run: (targetEditor) => targetEditor.chain().focus().toggleOrderedList().run()
      },
      {
        id: "todo",
        label: "Todo",
        description: "체크리스트",
        group: "basic",
        aliases: ["/todo", "/check", "/할일"],
        keywords: ["task", "todo", "check", "체크리스트", "할일", "to-do"],
        run: (targetEditor) => targetEditor.chain().focus().toggleTaskList().run()
      },
      {
        id: "toggle",
        label: "Toggle",
        description: "접기/펼치기 블록",
        group: "basic",
        aliases: ["/toggle", "/details", "/토글"],
        keywords: ["toggle", "collapse", "details", "접기", "펼치기"],
        run: insertToggle
      },
      {
        id: "quote",
        label: "Quote",
        description: "인용 블록",
        group: "basic",
        aliases: ["/quote", "/인용"],
        keywords: ["quote", "blockquote", "인용", "명언"],
        run: (targetEditor) => targetEditor.chain().focus().toggleBlockquote().run()
      },
      {
        id: "callout",
        label: "Callout",
        description: "강조 안내 블록",
        group: "basic",
        aliases: ["/callout", "/콜아웃"],
        keywords: ["callout", "notice", "hint", "주의", "안내", "강조"],
        run: insertCallout
      },
      {
        id: "divider",
        label: "Divider",
        description: "구분선",
        group: "basic",
        aliases: ["/divider", "/hr", "/구분선"],
        keywords: ["divider", "hr", "line", "구분선", "선"],
        run: (targetEditor) => targetEditor.chain().focus().setHorizontalRule().run()
      },
      {
        id: "code",
        label: "Code",
        description: "코드 블록",
        group: "basic",
        aliases: ["/code", "/코드"],
        keywords: ["code", "snippet", "소스", "코드"],
        run: (targetEditor) => targetEditor.chain().focus().toggleCodeBlock().run()
      },
      {
        id: "table",
        label: "Table",
        description: "간단 표",
        group: "data",
        aliases: ["/table", "/표"],
        keywords: ["table", "grid", "표", "테이블", "csv"],
        run: (targetEditor) => targetEditor.chain().focus().insertTable({ rows: 3, cols: 3, withHeaderRow: true }).run()
      },
      {
        id: "toc",
        label: "TOC",
        description: "문서 목차",
        group: "data",
        aliases: ["/toc", "/목차"],
        keywords: ["toc", "목차", "heading", "차례"],
        run: (targetEditor) => targetEditor.chain().focus().insertContent({ type: "tocBlock" }).run()
      },
      {
        id: "image",
        label: "Image",
        description: "이미지 업로드",
        group: "media",
        aliases: ["/image", "/img", "/이미지"],
        keywords: ["image", "img", "photo", "picture", "이미지", "사진"],
        run: () => openUploadDialog("image")
      },
      {
        id: "file",
        label: "File",
        description: "파일 업로드",
        group: "media",
        aliases: ["/file", "/파일"],
        keywords: ["file", "attachment", "upload", "문서", "첨부"],
        run: () => openUploadDialog("file")
      }
    ];
  }, [openEmojiPicker, openUploadDialog]);

  const filteredSlashItems = useMemo(() => filterSlashItems(slashItems, slash.query), [slash.query, slashItems]);

  const recommendedSlashItems = useMemo(() => {
    if (slash.query.trim()) {
      return [];
    }
    return buildRecommendedItems(slashItems, recentSlashIds, 6);
  }, [recentSlashIds, slash.query, slashItems]);

  const visibleSlashSections = useMemo(() => {
    const recommendedIds = new Set(recommendedSlashItems.map((item) => item.id));
    const baseItems = slash.query.trim() ? filteredSlashItems : filteredSlashItems.filter((item) => !recommendedIds.has(item.id));

    const buckets: Record<Exclude<SlashMenuItem["group"], "recommend">, SlashItem[]> = {
      basic: [],
      media: [],
      data: []
    };
    baseItems.forEach((item) => {
      buckets[item.group].push(item);
    });

    const sections: Array<{ title: string; items: SlashItem[] }> = [];
    if (recommendedSlashItems.length > 0) {
      sections.push({ title: "추천", items: recommendedSlashItems });
    }
    if (buckets.basic.length > 0) {
      sections.push({ title: "기본", items: buckets.basic });
    }
    if (buckets.media.length > 0) {
      sections.push({ title: "미디어", items: buckets.media });
    }
    if (buckets.data.length > 0) {
      sections.push({ title: "데이터", items: buckets.data });
    }
    return sections;
  }, [filteredSlashItems, recommendedSlashItems, slash.query]);

  const flatSlashItems = useMemo(() => visibleSlashSections.flatMap((section) => section.items), [visibleSlashSections]);

  const applySlashItem = useCallback(
    (item: SlashItem) => {
      if (!editor) {
        return;
      }
      deleteSlashRange(editor);
      item.run(editor);
      registerRecentSlashItem(item.id);
      editor.commands.focus();
    },
    [deleteSlashRange, editor, registerRecentSlashItem]
  );

  useEffect(() => {
    if (!editor) {
      return;
    }

    const refreshSlash = () => {
      if (disabled) {
        closeSlashMenu();
        return;
      }
      const selection = editor.state.selection;
      if (!selection.empty) {
        closeSlashMenu();
        return;
      }

      const { $from } = selection;
      if (!$from.parent.isTextblock) {
        closeSlashMenu();
        return;
      }
      if ($from.parent.type.name === "codeBlock") {
        closeSlashMenu();
        return;
      }

      if (isComposingRef.current) {
        closeSlashMenu();
        return;
      }

      const before = $from.parent.textBetween(0, $from.parentOffset, undefined, "\n");
      const match = before.match(/(?:^|\s)\/([^\s/]*)$/);
      if (!match) {
        closeSlashMenu();
        return;
      }

      const slashText = `/${match[1]}`;
      const from = selection.from - slashText.length;
      const to = selection.from;
      setSlashPosition(editor, from, to, match[1]);
    };

    const onUpdate = () => refreshSlash();
    const onSelectionUpdate = () => refreshSlash();
    editor.on("update", onUpdate);
    editor.on("selectionUpdate", onSelectionUpdate);

    return () => {
      editor.off("update", onUpdate);
      editor.off("selectionUpdate", onSelectionUpdate);
    };
  }, [closeSlashMenu, disabled, editor, setSlashPosition]);

  useEffect(() => {
    if (!slash.open) {
      return;
    }
    if (slashIndex >= flatSlashItems.length) {
      setSlashIndex(0);
    }
  }, [flatSlashItems.length, slash.open, slashIndex]);

  useEffect(() => {
    slashItemRefs.current = slashItemRefs.current.slice(0, flatSlashItems.length);
  }, [flatSlashItems.length]);

  useEffect(() => {
    if (!slash.open || flatSlashItems.length === 0) {
      return;
    }
    const targetIndex = Math.min(Math.max(slashIndex, 0), flatSlashItems.length - 1);
    const rafId = window.requestAnimationFrame(() => {
      const target = slashItemRefs.current[targetIndex];
      target?.scrollIntoView({
        block: "nearest",
        inline: "nearest"
      });
    });
    return () => {
      window.cancelAnimationFrame(rafId);
    };
  }, [flatSlashItems.length, slash.open, slashIndex]);

  useEffect(() => {
    if (!editor) {
      return;
    }

    const keyHandler = (event: KeyboardEvent) => {
      if (emojiPicker.open && event.key === "Escape") {
        event.preventDefault();
        event.stopPropagation();
        closeEmojiPicker();
        return;
      }
      if (!slashRef.current.open) {
        return;
      }
      if (isComposingRef.current) {
        return;
      }
      if (event.key === "ArrowDown") {
        event.preventDefault();
        event.stopPropagation();
        setSlashIndex((prev) => {
          if (flatSlashItems.length === 0) {
            return 0;
          }
          return (prev + 1) % flatSlashItems.length;
        });
        return;
      }
      if (event.key === "ArrowUp") {
        event.preventDefault();
        event.stopPropagation();
        setSlashIndex((prev) => {
          if (flatSlashItems.length === 0) {
            return 0;
          }
          return (prev - 1 + flatSlashItems.length) % flatSlashItems.length;
        });
        return;
      }
      if (event.key === "Enter") {
        event.preventDefault();
        event.stopPropagation();
        const item = flatSlashItems[slashIndex] ?? flatSlashItems[0];
        if (!item) {
          closeSlashMenu();
          return;
        }
        applySlashItem(item);
        return;
      }
      if (event.key === "Escape") {
        event.preventDefault();
        event.stopPropagation();
        closeSlashMenu();
      }
    };

    editor.view.dom.addEventListener("keydown", keyHandler, true);
    return () => {
      editor.view.dom.removeEventListener("keydown", keyHandler, true);
    };
  }, [applySlashItem, closeEmojiPicker, closeSlashMenu, editor, emojiPicker.open, flatSlashItems, slashIndex]);

  useEffect(() => {
    if (!editor || !slash.open) {
      return;
    }

    const handleOutside = (event: MouseEvent) => {
      const target = event.target;
      if (!(target instanceof Element)) {
        return;
      }
      if (editor.view.dom.contains(target)) {
        return;
      }
      if (slashMenuRef.current?.contains(target)) {
        return;
      }
      closeSlashMenu();
    };

    window.addEventListener("mousedown", handleOutside);
    return () => {
      window.removeEventListener("mousedown", handleOutside);
    };
  }, [closeSlashMenu, editor, slash.open]);

  useEffect(() => {
    if (!emojiPicker.open) {
      return;
    }

    const handleOutside = (event: MouseEvent) => {
      const target = event.target;
      if (!(target instanceof Element)) {
        return;
      }
      if (emojiPickerRef.current?.contains(target)) {
        return;
      }
      closeEmojiPicker();
    };

    window.addEventListener("mousedown", handleOutside);
    return () => {
      window.removeEventListener("mousedown", handleOutside);
    };
  }, [closeEmojiPicker, emojiPicker.open]);

  useEffect(() => {
    if (!emojiPicker.open) {
      return;
    }
    const handleViewportChange = () => {
      closeEmojiPicker();
    };
    window.addEventListener("resize", handleViewportChange);
    window.addEventListener("scroll", handleViewportChange, true);
    return () => {
      window.removeEventListener("resize", handleViewportChange);
      window.removeEventListener("scroll", handleViewportChange, true);
    };
  }, [closeEmojiPicker, emojiPicker.open]);

  useEffect(() => {
    if (!editor || !slash.open) {
      return;
    }
    const refreshPosition = () => {
      const current = slashRef.current;
      if (!current.open) {
        return;
      }
      setSlashPosition(editor, current.from, current.to, current.query);
    };
    window.addEventListener("scroll", refreshPosition, true);
    window.addEventListener("resize", refreshPosition);
    return () => {
      window.removeEventListener("scroll", refreshPosition, true);
      window.removeEventListener("resize", refreshPosition);
    };
  }, [editor, setSlashPosition, slash.open]);

  const renderSlashItem = (item: SlashItem, index: number) => {
    const active = slashIndex === index;
    return (
      <button
        className={`editor-v2-slash-item${active ? " is-active" : ""}`}
        key={item.id}
        ref={(node) => {
          slashItemRefs.current[index] = node;
        }}
        onClick={(event) => {
          event.preventDefault();
          applySlashItem(item);
        }}
        type="button"
      >
        <span className="editor-v2-slash-item-row">
          <span className="editor-v2-slash-item-label">{item.label}</span>
          {item.aliases?.[0] ? <span className="editor-v2-slash-item-alias">{item.aliases[0]}</span> : null}
        </span>
        <span className="editor-v2-slash-item-desc">{item.description}</span>
      </button>
    );
  };

  return (
    <div className="editor-v2-shell" data-doc-id={docId}>
      <input
        accept="image/*"
        className="hidden-input"
        onChange={(event) => {
          void handleUploadFiles(event.target.files);
          event.currentTarget.value = "";
        }}
        ref={imageInputRef}
        type="file"
      />
      <input
        className="hidden-input"
        onChange={(event) => {
          void handleUploadFiles(event.target.files);
          event.currentTarget.value = "";
        }}
        ref={fileInputRef}
        type="file"
      />

      <div className="editor-v2-toolbar" role="toolbar" aria-label="블록 에디터 도구">
        <button className="editor-v2-toolbar-btn" onClick={() => editor?.chain().focus().toggleHeading({ level: 1 }).run()} type="button" disabled={disabled}>
          H1
        </button>
        <button className="editor-v2-toolbar-btn" onClick={() => editor?.chain().focus().toggleHeading({ level: 2 }).run()} type="button" disabled={disabled}>
          H2
        </button>
        <button className="editor-v2-toolbar-btn" onClick={() => editor?.chain().focus().toggleBulletList().run()} type="button" disabled={disabled}>
          <List size={14} />
        </button>
        <button className="editor-v2-toolbar-btn" onClick={() => editor?.chain().focus().toggleOrderedList().run()} type="button" disabled={disabled}>
          <ListOrdered size={14} />
        </button>
        <button className="editor-v2-toolbar-btn" onClick={() => editor?.chain().focus().toggleTaskList().run()} type="button" disabled={disabled}>
          <ListChecks size={14} />
        </button>
        <button className="editor-v2-toolbar-btn" onClick={() => editor?.chain().focus().toggleBlockquote().run()} type="button" disabled={disabled}>
          <Quote size={14} />
        </button>
        <button className="editor-v2-toolbar-btn" onClick={() => editor?.chain().focus().setHorizontalRule().run()} type="button" disabled={disabled}>
          Divider
        </button>
        <button
          className="editor-v2-toolbar-btn"
          onClick={() => editor?.chain().focus().insertTable({ rows: 3, cols: 3, withHeaderRow: true }).run()}
          type="button"
          disabled={disabled}
        >
          <Table2 size={14} />
        </button>
        <label className="editor-v2-code-language">
          <span>Code</span>
          <select
            onChange={(event) => {
              setCodeLanguage(event.target.value);
              editor?.chain().focus().updateAttributes("codeBlock", { language: event.target.value }).run();
            }}
            value={codeLanguage}
            disabled={disabled}
          >
            {CODE_LANGUAGES.map((language) => (
              <option key={language} value={language}>
                {language}
              </option>
            ))}
          </select>
        </label>
        {selectedImageWidth ? (
          <label className="editor-v2-code-language">
            <span>Image</span>
            <select
              onChange={(event) => {
                const width = event.target.value;
                setSelectedImageWidth(width);
                editor?.chain().focus().updateAttributes("image", { width }).run();
              }}
              value={selectedImageWidth}
              disabled={disabled}
            >
              {IMAGE_WIDTH_OPTIONS.map((width) => (
                <option key={width} value={width}>
                  {width}
                </option>
              ))}
            </select>
          </label>
        ) : null}
        <button className="editor-v2-toolbar-btn" onClick={() => void copyCurrentCodeBlock()} type="button" disabled={disabled}>
          Code 복사
        </button>
        <button className="editor-v2-toolbar-btn" onClick={() => openUploadDialog("image")} type="button" disabled={disabled}>
          <ImagePlus size={14} />
        </button>
        <button className="editor-v2-toolbar-btn" onClick={() => openUploadDialog("file")} type="button" disabled={disabled}>
          <Upload size={14} />
        </button>
        <button className="editor-v2-toolbar-btn mobile-only" onClick={() => moveCurrentBlock("up")} type="button" disabled={disabled}>
          <ChevronRight size={14} /> 위
        </button>
        <button className="editor-v2-toolbar-btn mobile-only" onClick={() => moveCurrentBlock("down")} type="button" disabled={disabled}>
          <ChevronDown size={14} /> 아래
        </button>
      </div>

      <div className={`editor-v2-surface${isDragOver ? " is-drag-over" : ""}`} ref={editorSurfaceRef}>
        <p aria-hidden={!isDragOver} className={`editor-v2-drop-hint${isDragOver ? " is-visible" : ""}`}>
          파일을 놓으면 첨부됩니다.
        </p>
        {editor ? (
          <DragHandle editor={editor} className="editor-v2-drag-handle" onNodeChange={() => undefined}>
            <button className="editor-v2-handle-btn" type="button" tabIndex={-1}>
              <GripVertical size={14} />
            </button>
          </DragHandle>
        ) : null}
        <EditorContent editor={editor} />
      </div>

      {slash.open ? (
        <div className="editor-v2-slash" ref={slashMenuRef} style={{ left: slash.x, top: slash.y }}>
          {flatSlashItems.length === 0 ? <p className="editor-v2-slash-empty">일치하는 블록이 없습니다.</p> : null}
          {visibleSlashSections.map((section, sectionIndex) => {
            const before = visibleSlashSections.slice(0, sectionIndex).reduce((acc, value) => acc + value.items.length, 0);
            return (
              <div className="editor-v2-slash-section" key={`${section.title}-${sectionIndex}`}>
                <p className="editor-v2-slash-title">{section.title}</p>
                {section.items.map((item, offset) => renderSlashItem(item, before + offset))}
              </div>
            );
          })}
        </div>
      ) : null}

      {emojiPicker.open ? (
        <div className="editor-v2-emoji-picker" ref={emojiPickerRef} style={{ left: emojiPicker.x, top: emojiPicker.y }}>
          <p className="editor-v2-emoji-picker-title">이모지</p>
          <div className="editor-v2-emoji-grid">
            {EMOJI_OPTIONS.map((option) => (
              <button
                aria-label={`${option.label} ${option.value}`}
                className="editor-v2-emoji-btn"
                key={option.value}
                onClick={(event) => {
                  event.preventDefault();
                  insertEmojiAtCursor(option.value);
                }}
                title={option.label}
                type="button"
              >
                {option.value}
              </button>
            ))}
          </div>
        </div>
      ) : null}

      <div className="editor-v2-footnote">
        <p className="muted">슬래시 메뉴: <code>/</code> · 저장은 Cmd/Ctrl + S</p>
        <p className="muted">마크다운 동기화 미리보기: {blockDocToMarkdown(normalizeBlockDoc(editor?.getJSON() ?? value)).slice(0, 120) || "(비어 있음)"}</p>
        {uploadProgress !== null ? <p className="muted">업로드 진행률: {Math.round(uploadProgress)}%</p> : null}
        {uploadError ? <p className="error-inline">{uploadError}</p> : null}
      </div>
    </div>
  );
}
