export type SlashMenuGroup = "recommend" | "basic" | "media" | "data";

export type SlashMenuItem = {
  id: string;
  label: string;
  description: string;
  group: Exclude<SlashMenuGroup, "recommend">;
  keywords: string[];
  aliases?: string[];
};

const collapse = (value: string): string =>
  value
    .toLowerCase()
    .trim()
    .replace(/^\//, "")
    .replace(/\s+/g, "");

const normalize = (value: string): string => value.toLowerCase().trim().replace(/^\//, "");

const scoreNeedle = (haystack: string, query: string): number => {
  if (!haystack || !query) {
    return 0;
  }
  if (haystack === query) {
    return 100;
  }
  if (haystack.startsWith(query)) {
    return 75;
  }
  if (haystack.includes(query)) {
    return 55;
  }
  const compactHaystack = collapse(haystack);
  const compactQuery = collapse(query);
  if (!compactHaystack || !compactQuery) {
    return 0;
  }
  if (compactHaystack.startsWith(compactQuery)) {
    return 45;
  }
  if (compactHaystack.includes(compactQuery)) {
    return 35;
  }
  let cursor = 0;
  for (const ch of compactHaystack) {
    if (ch === compactQuery[cursor]) {
      cursor += 1;
      if (cursor >= compactQuery.length) {
        return 22;
      }
    }
  }
  return 0;
};

export const filterSlashItems = <T extends SlashMenuItem>(items: T[], rawQuery: string): T[] => {
  const query = normalize(rawQuery);
  if (!query) {
    return items;
  }

  return items
    .map((item, idx) => {
      const fields = [item.label, item.description, ...item.keywords, ...(item.aliases ?? [])];
      const score = fields.reduce((acc, field) => Math.max(acc, scoreNeedle(field, query)), 0);
      return { item, score, idx };
    })
    .filter((entry) => entry.score > 0)
    .sort((a, b) => {
      if (b.score !== a.score) {
        return b.score - a.score;
      }
      return a.idx - b.idx;
    })
    .map((entry) => entry.item);
};

export const buildRecommendedItems = <T extends SlashMenuItem>(
  allItems: T[],
  recentIds: string[],
  maxCount: number
): T[] => {
  if (maxCount <= 0) {
    return [];
  }
  const byId = new Map(allItems.map((item) => [item.id, item]));
  const fromRecent = recentIds.map((id) => byId.get(id)).filter((item): item is T => Boolean(item));
  const fallback = ["text", "h2", "todo", "callout", "image", "table", "code"]
    .map((id) => byId.get(id))
    .filter((item): item is T => Boolean(item));

  const merged = [...fromRecent, ...fallback];
  const deduped: T[] = [];
  const seen = new Set<string>();
  for (const item of merged) {
    if (seen.has(item.id)) {
      continue;
    }
    seen.add(item.id);
    deduped.push(item);
    if (deduped.length >= maxCount) {
      break;
    }
  }
  return deduped;
};
