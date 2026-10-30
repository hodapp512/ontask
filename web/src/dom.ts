/** Tiny DOM helper: h("div", {class:"x", onclick: fn}, child, "text"). */
type Child = Node | string | null | undefined | false;
export function h<K extends keyof HTMLElementTagNameMap>(tag: K, props: Record<string, any> = {}, ...kids: (Child | Child[])[]): HTMLElementTagNameMap[K] {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(props)) {
    if (v === undefined || v === null || v === false) continue;
    if (k.startsWith("on") && typeof v === "function") el.addEventListener(k.slice(2), v);
    else if (k === "class") el.className = v;
    else if (k === "value") (el as any).value = v;
    else if (k === "checked" || k === "disabled" || k === "selected") (el as any)[k] = !!v;
    else el.setAttribute(k, String(v));
  }
  for (const kid of kids.flat()) if (kid !== null && kid !== undefined && kid !== false) el.append(kid instanceof Node ? kid : document.createTextNode(kid));
  return el;
}
export const clear = (el: HTMLElement) => { while (el.firstChild) el.removeChild(el.firstChild); };
