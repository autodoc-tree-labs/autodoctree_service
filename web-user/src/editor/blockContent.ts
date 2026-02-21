export type BlockMark = {
  type: string;
  attrs?: Record<string, unknown>;
};

export type BlockNode = {
  type: string;
  attrs?: Record<string, unknown>;
  content?: BlockNode[];
  text?: string;
  marks?: BlockMark[];
};

export type BlockDoc = {
  type: "doc";
  content: BlockNode[];
};

const MAX_HEADING_LEVEL = 3;

const textNode = (text: string): BlockNode => ({ type: "text", text });

const paragraphWithText = (text: string): BlockNode => ({
  type: "paragraph",
  content: text ? [textNode(text)] : []
});

const listItemWithText = (text: string): BlockNode => ({
  type: "listItem",
  content: [paragraphWithText(text)]
});

const asArray = (value: unknown): BlockNode[] => (Array.isArray(value) ? (value as BlockNode[]) : []);

const toDoc = (content: BlockNode[]): BlockDoc => ({
  type: "doc",
  content: content.length > 0 ? content : [paragraphWithText("")]
});

export const isBlockDoc = (value: unknown): value is BlockDoc => {
  if (!value || typeof value !== "object") {
    return false;
  }
  const candidate = value as { type?: unknown; content?: unknown };
  return candidate.type === "doc" && Array.isArray(candidate.content);
};

export const normalizeBlockDoc = (value: unknown, fallbackMarkdown = ""): BlockDoc => {
  if (isBlockDoc(value)) {
    return toDoc(value.content);
  }
  if (Array.isArray(value)) {
    return toDoc(value as BlockNode[]);
  }
  if (typeof value === "string" && value.trim()) {
    return markdownToBlockDoc(value);
  }
  return markdownToBlockDoc(fallbackMarkdown);
};

export const markdownToBlockDoc = (markdown: string): BlockDoc => {
  const source = markdown.replace(/\r\n/g, "\n");
  const lines = source.split("\n");
  const content: BlockNode[] = [];

  let index = 0;
  while (index < lines.length) {
    const current = lines[index].trimEnd();

    if (!current.trim()) {
      index += 1;
      continue;
    }

    if (current.startsWith("```") || current.startsWith("~~~")) {
      const fence = current.startsWith("~~~") ? "~~~" : "```";
      const language = current.slice(fence.length).trim();
      const codeLines: string[] = [];
      index += 1;
      while (index < lines.length && !lines[index].trimStart().startsWith(fence)) {
        codeLines.push(lines[index]);
        index += 1;
      }
      if (index < lines.length) {
        index += 1;
      }
      content.push({
        type: "codeBlock",
        attrs: language ? { language } : undefined,
        content: [textNode(codeLines.join("\n"))]
      });
      continue;
    }

    const headingMatch = current.match(/^(#{1,6})\s+(.+)$/);
    if (headingMatch) {
      const level = Math.min(headingMatch[1].length, MAX_HEADING_LEVEL);
      content.push({
        type: "heading",
        attrs: { level },
        content: [textNode(headingMatch[2].trim())]
      });
      index += 1;
      continue;
    }

    if (/^([-*_])\1\1+$/.test(current.trim())) {
      content.push({ type: "horizontalRule" });
      index += 1;
      continue;
    }

    if (/^\[toc\]$/i.test(current.trim())) {
      content.push({ type: "tocBlock" });
      index += 1;
      continue;
    }

    if (/^>\s*\[![a-z]+\]/i.test(current.trim())) {
      const calloutText = current.replace(/^>\s*\[![a-z]+\]\s*/i, "").trim() || "Callout";
      content.push({
        type: "callout",
        attrs: { icon: "💡", tone: "default" },
        content: [paragraphWithText(calloutText)]
      });
      index += 1;
      continue;
    }

    if (current.trimStart().startsWith(">")) {
      const quoteText = current.replace(/^>\s?/, "").trim();
      content.push({
        type: "blockquote",
        content: [paragraphWithText(quoteText)]
      });
      index += 1;
      continue;
    }

    const taskMatch = current.match(/^-\s*\[( |x|X)]\s+(.+)$/);
    if (taskMatch) {
      const taskItems: BlockNode[] = [];
      while (index < lines.length) {
        const row = lines[index].trimEnd();
        const match = row.match(/^-\s*\[( |x|X)]\s+(.+)$/);
        if (!match) {
          break;
        }
        taskItems.push({
          type: "taskItem",
          attrs: { checked: match[1].toLowerCase() === "x" },
          content: [paragraphWithText(match[2].trim())]
        });
        index += 1;
      }
      content.push({ type: "taskList", content: taskItems });
      continue;
    }

    const bulletMatch = current.match(/^[-*+]\s+(.+)$/);
    if (bulletMatch) {
      const items: BlockNode[] = [];
      while (index < lines.length) {
        const row = lines[index].trimEnd();
        const match = row.match(/^[-*+]\s+(.+)$/);
        if (!match) {
          break;
        }
        items.push(listItemWithText(match[1].trim()));
        index += 1;
      }
      content.push({ type: "bulletList", content: items });
      continue;
    }

    const orderedMatch = current.match(/^\d+\.\s+(.+)$/);
    if (orderedMatch) {
      const items: BlockNode[] = [];
      while (index < lines.length) {
        const row = lines[index].trimEnd();
        const match = row.match(/^\d+\.\s+(.+)$/);
        if (!match) {
          break;
        }
        items.push(listItemWithText(match[1].trim()));
        index += 1;
      }
      content.push({ type: "orderedList", content: items });
      continue;
    }

    const paragraphParts = [current.trim()];
    index += 1;
    while (index < lines.length) {
      const row = lines[index].trimEnd();
      if (!row.trim()) {
        break;
      }
      if (
        row.startsWith("#") ||
        row.startsWith(">") ||
        row.startsWith("```") ||
        row.startsWith("~~~") ||
        /^[-*+]\s+/.test(row) ||
        /^\d+\.\s+/.test(row) ||
        /^-\s*\[( |x|X)]\s+/.test(row)
      ) {
        break;
      }
      paragraphParts.push(row.trim());
      index += 1;
    }
    content.push(paragraphWithText(paragraphParts.join(" ")));
  }

  return toDoc(content);
};

const renderInline = (nodes: BlockNode[] | undefined): string => {
  if (!nodes || nodes.length === 0) {
    return "";
  }
  return nodes
    .map((node) => {
      if (node.type === "text") {
        let text = node.text ?? "";
        (node.marks ?? []).forEach((mark) => {
          if (mark.type === "bold") {
            text = `**${text}**`;
          } else if (mark.type === "italic") {
            text = `*${text}*`;
          } else if (mark.type === "code") {
            text = `\`${text}\``;
          } else if (mark.type === "strike") {
            text = `~~${text}~~`;
          } else if (mark.type === "link") {
            const href = typeof mark.attrs?.href === "string" ? mark.attrs.href : "";
            text = href ? `[${text}](${href})` : text;
          }
        });
        return text;
      }
      return renderInline(node.content);
    })
    .join("")
    .trimEnd();
};

const renderParagraphText = (node: BlockNode): string => {
  if (!node.content || node.content.length === 0) {
    return "";
  }
  return node.content
    .map((child) => {
      if (child.type === "paragraph") {
        return renderInline(child.content);
      }
      return renderNode(child);
    })
    .filter(Boolean)
    .join("\n");
};

const renderTable = (node: BlockNode): string => {
  const rows = asArray(node.content).map((row) => {
    return asArray(row.content).map((cell) => {
      return asArray(cell.content)
        .map((paragraph) => renderInline(paragraph.content))
        .join(" ")
        .trim();
    });
  });

  const width = rows.reduce((acc, row) => Math.max(acc, row.length), 1);
  const header = (rows[0] ?? [""]).concat(Array.from({ length: Math.max(0, width - (rows[0]?.length ?? 0)) }, () => " "));
  const body = rows
    .slice(1)
    .map((row) => row.concat(Array.from({ length: Math.max(0, width - row.length) }, () => " ")));

  const lines: string[] = [];
  lines.push(`| ${header.join(" | ")} |`);
  lines.push(`| ${Array.from({ length: width }, () => "---").join(" | ")} |`);
  (body.length > 0 ? body : [Array.from({ length: width }, () => " ")]).forEach((row) => {
    lines.push(`| ${row.join(" | ")} |`);
  });
  return lines.join("\n");
};

const renderNode = (node: BlockNode): string => {
  switch (node.type) {
    case "paragraph":
      return renderInline(node.content);
    case "heading": {
      const levelRaw = Number(node.attrs?.level ?? 1);
      const level = Number.isFinite(levelRaw) ? Math.max(1, Math.min(MAX_HEADING_LEVEL, levelRaw)) : 1;
      return `${"#".repeat(level)} ${renderInline(node.content)}`.trimEnd();
    }
    case "bulletList":
      return asArray(node.content)
        .map((item) => `- ${renderParagraphText(item) || " "}`)
        .join("\n");
    case "orderedList":
      return asArray(node.content)
        .map((item, index) => `${index + 1}. ${renderParagraphText(item) || " "}`)
        .join("\n");
    case "taskList":
      return asArray(node.content)
        .map((item) => {
          const checked = Boolean(item.attrs?.checked);
          return `- [${checked ? "x" : " "}] ${renderParagraphText(item) || " "}`;
        })
        .join("\n");
    case "blockquote": {
      const quote = renderParagraphText(node);
      return quote
        .split("\n")
        .map((line) => (line ? `> ${line}` : ">"))
        .join("\n");
    }
    case "horizontalRule":
      return "---";
    case "codeBlock": {
      const language = typeof node.attrs?.language === "string" ? node.attrs.language : "";
      const code = asArray(node.content)
        .map((part) => part.text ?? "")
        .join("");
      return `${language ? `\`\`\`${language}` : "```"}\n${code}\n\`\`\``;
    }
    case "toggleBlock": {
      const title = typeof node.attrs?.title === "string" ? node.attrs.title : "Toggle";
      const body = asArray(node.content)
        .map((child) => renderNode(child))
        .filter(Boolean)
        .join("\n");
      return body ? `<details><summary>${title}</summary>\n${body}\n</details>` : `<details><summary>${title}</summary></details>`;
    }
    case "callout": {
      const body = renderParagraphText(node);
      return body ? `> [!NOTE] ${body}` : "> [!NOTE]";
    }
    case "image": {
      const src = typeof node.attrs?.src === "string" ? node.attrs.src : "";
      const alt = typeof node.attrs?.alt === "string" ? node.attrs.alt : "image";
      return `![${alt}](${src})`;
    }
    case "fileBlock": {
      const filename = typeof node.attrs?.filename === "string" ? node.attrs.filename : "file";
      const url = typeof node.attrs?.url === "string" ? node.attrs.url : "";
      const attachmentId = typeof node.attrs?.attachmentId === "string" ? node.attrs.attachmentId : "";
      return url ? `[${filename}](${url})` : `[${filename}](attachment:${attachmentId})`;
    }
    case "tocBlock":
      return "[TOC]";
    case "table":
      return renderTable(node);
    default:
      return asArray(node.content)
        .map((child) => renderNode(child))
        .filter(Boolean)
        .join("\n");
  }
};

export const blockDocToMarkdown = (doc: BlockDoc): string => {
  return asArray(doc.content)
    .map((node) => renderNode(node).trimEnd())
    .filter(Boolean)
    .join("\n\n")
    .trim();
};

export const blockDocToPlainText = (doc: BlockDoc): string => {
  const chunks: string[] = [];

  const walk = (node: BlockNode | undefined) => {
    if (!node) {
      return;
    }
    if (node.type === "text" && node.text) {
      chunks.push(node.text);
    }
    if (node.type === "image") {
      const alt = typeof node.attrs?.alt === "string" ? node.attrs.alt.trim() : "";
      if (alt) {
        chunks.push(alt);
      }
    }
    if (node.type === "fileBlock") {
      const filename = typeof node.attrs?.filename === "string" ? node.attrs.filename.trim() : "";
      if (filename) {
        chunks.push(filename);
      }
    }
    asArray(node.content).forEach((child) => walk(child));
  };

  doc.content.forEach((node) => walk(node));
  return chunks.join(" ").replace(/\s+/g, " ").trim();
};
