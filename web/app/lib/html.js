// One import point for rendering: Preact + htm tagged templates, no build step.
import { h, render, Fragment, createContext } from "../vendor/preact.js";
import htm from "../vendor/htm.js";

export const html = htm.bind(h);
export { h, render, Fragment, createContext };
export {
  useState, useEffect, useLayoutEffect, useRef, useMemo, useCallback,
  useReducer, useContext, useId,
} from "../vendor/hooks.js";

// Joins class names, skipping falsy entries: cx("card", seen && "is-seen").
export function cx(...names) {
  return names.filter(Boolean).join(" ");
}
