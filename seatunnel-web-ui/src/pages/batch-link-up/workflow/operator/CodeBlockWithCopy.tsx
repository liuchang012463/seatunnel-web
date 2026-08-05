import {
  CheckCircleOutlined,
  CloseOutlined,
  CopyOutlined,
} from "@ant-design/icons";
import { defaultKeymap } from "@codemirror/commands";
import {
  HighlightStyle,
  StreamLanguage,
  syntaxHighlighting,
} from "@codemirror/language";
import { EditorState } from "@codemirror/state";
import { EditorView, keymap, lineNumbers } from "@codemirror/view";
import { tags } from "@lezer/highlight";
import React, { useEffect, useRef, useState } from "react";

interface CodeBlockWithCopyProps {
  content?: string;
  height?: number;
  title?: string;
  onClose?: () => void;
}

type HoconState = {
  inBlockComment: boolean;
};

const hoconLanguage = StreamLanguage.define<HoconState>({
  startState() {
    return { inBlockComment: false };
  },

  token(stream, state) {
    if (state.inBlockComment) {
      while (!stream.eol()) {
        const ch = stream.next();
        if (ch === "*" && stream.peek() === "/") {
          stream.next();
          state.inBlockComment = false;
          break;
        }
      }
      return "comment";
    }

    if (stream.eatSpace()) return null;

    if (stream.match("#")) {
      stream.skipToEnd();
      return "comment";
    }

    if (stream.match("//")) {
      stream.skipToEnd();
      return "comment";
    }

    if (stream.match("/*")) {
      state.inBlockComment = true;
      return "comment";
    }

    if (
      stream.match("{") ||
      stream.match("}") ||
      stream.match("[") ||
      stream.match("]")
    ) {
      return "brace";
    }

    if (stream.match("=") || stream.match(":") || stream.match(",")) {
      return "operator";
    }

    if (stream.match(/"(?:[^"\\]|\\.)*"?/)) {
      return "string";
    }

    if (stream.match(/\b(true|false|null)\b/)) {
      return "bool";
    }

    if (stream.match(/\b\d+(\.\d+)?\b/)) {
      return "number";
    }

    if (stream.match(/\b(env|job|source|sink|transform|include)\b/)) {
      return "keyword";
    }

    if (stream.match(/[A-Za-z_][\w.-]*/)) {
      const cur = stream.current();
      const rest = stream.string.slice(stream.pos);

      if (/^\s*[=:]/.test(rest)) {
        return "propertyName";
      }

      if (/^[A-Z][\w-]*/.test(cur)) {
        return "typeName";
      }

      return "variableName";
    }

    stream.next();
    return null;
  },
});

const hoconHighlightStyle = HighlightStyle.define([
  {
    tag: tags.keyword,
    color: "#4DD2FF",
    fontWeight: "600",
  },
  {
    tag: tags.comment,
    color: "#8DA6AE",
    fontStyle: "italic",
  },
  {
    tag: tags.string,
    color: "#64E6B1",
  },
  {
    tag: tags.number,
    color: "#FFD166",
    fontWeight: "500",
  },
  {
    tag: tags.bool,
    color: "#C4B5FD",
    fontWeight: "600",
  },
  {
    tag: tags.propertyName,
    color: "#D5D5D5",
    fontWeight: "500",
  },
  {
    tag: tags.typeName,
    color: "#A78BFA",
    fontWeight: "600",
  },
  {
    tag: tags.variableName,
    color: "#D5D5D5",
  },
  {
    tag: [tags.brace, tags.squareBracket],
    color: "#4DD2FF",
    fontWeight: "600",
  },
  {
    tag: tags.operator,
    color: "#8DA6AE",
  },
]);

const createPreviewTheme = (height: number) =>
  EditorView.theme({
    "&": {
      height: `calc(${height}px - 58px)`,
      fontSize: "13px",
      backgroundColor: "var(--st-color-bg-panel)",
      color: "var(--st-color-text-primary)",
      outline: "none !important",
    },

    "&.cm-focused": {
      outline: "none !important",
    },

    ".cm-scroller": {
      overflow: "auto",
      fontFamily:
        'JetBrains Mono, Fira Code, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", monospace',
      lineHeight: "28px",
      outline: "none !important",
    },

    ".cm-content": {
      padding: "14px 0 16px 0",
      caretColor: "transparent",
      outline: "none !important",
    },

    ".cm-line": {
      padding: "0 20px 0 14px",
    },

    ".cm-gutters": {
      backgroundColor: "var(--st-color-bg-elevated)",
      color: "var(--st-color-text-secondary)",
      borderRight: "1px solid var(--st-color-border)",
      padding: "14px 0 16px 0",
    },

    ".cm-lineNumbers": {
      minWidth: "44px",
    },

    ".cm-lineNumbers .cm-gutterElement": {
      padding: "0 12px 0 14px",
      fontSize: "12px",
      lineHeight: "28px",
      color: "var(--st-color-text-secondary)",
    },

    ".cm-activeLine": {
      backgroundColor: "var(--st-color-hover)",
    },

    ".cm-activeLineGutter": {
      backgroundColor: "var(--st-color-selected)",
      color: "var(--st-color-text-primary)",
    },

    ".cm-selectionBackground": {
      backgroundColor: "var(--st-color-focus) !important",
    },

    "&.cm-focused .cm-selectionBackground": {
      backgroundColor: "var(--st-color-focus) !important",
    },

    ".cm-cursor": {
      display: "none",
    },

    ".cm-scroller::-webkit-scrollbar": {
      width: "10px",
      height: "10px",
    },

    ".cm-scroller::-webkit-scrollbar-track": {
      background: "transparent",
    },

    ".cm-scroller::-webkit-scrollbar-thumb": {
      background: "rgba(33, 135, 168, 0.72)",
      border: "2px solid transparent",
      borderRadius: "999px",
      backgroundClip: "padding-box",
    },

    ".cm-scroller::-webkit-scrollbar-thumb:hover": {
      background: "#4DD2FF",
      backgroundClip: "padding-box",
    },
  });

const CodeBlockWithCopy: React.FC<CodeBlockWithCopyProps> = ({
  content = "",
  height = 460,
  title = "HOCON Preview",
  onClose,
}) => {
  const [copied, setCopied] = useState(false);
  const containerRef = useRef<HTMLDivElement | null>(null);
  const viewRef = useRef<EditorView | null>(null);

  const handleCopy = async () => {
    try {
      await navigator.clipboard.writeText(content);
      setCopied(true);
      setTimeout(() => setCopied(false), 1800);
    } catch (err) {
      const textArea = document.createElement("textarea");
      textArea.value = content;
      textArea.style.position = "fixed";
      textArea.style.opacity = "0";
      document.body.appendChild(textArea);
      textArea.select();

      try {
        document.execCommand("copy");
        setCopied(true);
        setTimeout(() => setCopied(false), 1800);
      } finally {
        document.body.removeChild(textArea);
      }
    }
  };

  useEffect(() => {
    if (!containerRef.current) return;
    if (viewRef.current) return;

    const state = EditorState.create({
      doc: content,
      extensions: [
        keymap.of(defaultKeymap),
        hoconLanguage,
        syntaxHighlighting(hoconHighlightStyle),
        lineNumbers(),
        createPreviewTheme(height),
        EditorView.lineWrapping,
        EditorState.readOnly.of(true),
        EditorView.editable.of(false),
      ],
    });

    viewRef.current = new EditorView({
      state,
      parent: containerRef.current,
    });

    return () => {
      viewRef.current?.destroy();
      viewRef.current = null;
    };
  }, []);

  useEffect(() => {
    const view = viewRef.current;
    if (!view) return;

    const current = view.state.doc.toString();

    if (content !== current) {
      view.dispatch({
        changes: {
          from: 0,
          to: current.length,
          insert: content,
        },
      });
    }
  }, [content]);

  return (
    <div
      className="code-block-with-copy overflow-hidden rounded-[18px] border border-[var(--st-color-border)] bg-[var(--st-color-bg-panel)] shadow-[0_10px_30px_rgba(0,25,34,0.2)]"
      style={{ height }}
    >
      <div className="code-block-with-copy__header flex h-[58px] items-center justify-between border-b border-[var(--st-color-divider)] bg-[var(--st-color-bg-elevated)] px-4">
        <div className="flex min-w-0 items-center gap-2">
          <div className="code-block-with-copy__icon flex h-8 w-8 shrink-0 items-center justify-center rounded-xl border border-[var(--st-color-border)] bg-[var(--st-color-bg-control)] text-[var(--st-color-accent)] shadow-sm">
            <span className="font-semibold">H</span>
          </div>

          <div className="min-w-0">
            <div className="code-block-with-copy__title truncate text-sm font-semibold tracking-[-0.01em] text-[var(--st-color-text-primary)]">
              {title}
            </div>
          </div>
        </div>

        <div className="flex shrink-0 items-center gap-2">
          <button
            type="button"
            onClick={handleCopy}
            className={[
              "code-block-with-copy__copy-button inline-flex h-8 items-center gap-1.5 rounded-full border px-3 text-xs font-medium transition-all",
              copied
                ? "border-[rgba(53,211,153,0.34)] bg-[rgba(53,211,153,0.14)] text-[#64E6B1]"
                : "border-[var(--st-color-border)] bg-[var(--st-color-bg-control)] text-[#D5D5D5] hover:border-[var(--st-color-accent)] hover:bg-[var(--st-color-hover)] hover:text-white",
            ].join(" ")}
          >
            {copied ? <CheckCircleOutlined /> : <CopyOutlined />}
            <span>{copied ? "已复制" : "复制代码"}</span>
          </button>

          {onClose && (
            <button
              type="button"
              onClick={onClose}
              className="code-block-with-copy__close-button inline-flex h-8 w-8 items-center justify-center rounded-full text-[#D5D5D5] transition hover:bg-[var(--st-color-hover)] hover:text-[var(--st-color-accent)]"
              title="关闭"
            >
              <CloseOutlined />
            </button>
          )}
        </div>
      </div>

      <div ref={containerRef} className="code-block-with-copy__editor" />
    </div>
  );
};

export default CodeBlockWithCopy;
