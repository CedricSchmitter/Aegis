/*
 * Line icons of the shell, drawn on a 24 px grid with square caps like the rest of the design system (ADR-0019).
 * Decorative only: every icon sits next to a visible or accessible text.
 */
const PATHS = {
  today: (
    <>
      <polygon points="8,3 16,3 21,8 21,16 16,21 8,21 3,16 3,8" />
      <path d="M12 7v5l3 3" />
    </>
  ),
  documents: (
    <>
      <path d="M6 3h8l4 4v14H6z" />
      <path d="M14 3v4h4M9 12h6M9 16h6" />
    </>
  ),
  tasks: (
    <>
      <path d="M4 4h16v16H4z" />
      <path d="M8 12l3 3 5-6" />
    </>
  ),
  contracts: (
    <>
      <path d="M5 3h10l4 4v14H5z" />
      <path d="M8 10h8M8 14h5M13 18l2-2 3 3" />
    </>
  ),
  inbox: (
    <>
      <path d="M3 13l3-9h12l3 9v7H3z" />
      <path d="M3 13h5l1 3h6l1-3h5" />
    </>
  ),
  bell: (
    <>
      <path d="M6 16V10l3-5h6l3 5v6l2 2H4z" />
      <path d="M10 21h4" />
    </>
  ),
  upload: <path d="M12 16V4M6 10l6-6 6 6M4 20h16" />,
  trash: <path d="M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13" />,
  settings: (
    <>
      <polygon points="9,2 15,2 16,5 19,6 21,9 21,15 19,18 16,19 15,22 9,22 8,19 5,18 3,15 3,9 5,6 8,5" />
      <polygon points="10,9 14,9 15,12 14,15 10,15 9,12" />
    </>
  ),
  status: <path d="M3 12h4l2-6 4 12 2-6h6" />,
  logout: <path d="M14 4h6v16h-6M10 8l-4 4 4 4M6 12h10" />,
  folder: <path d="M3 6h7l2 2h9v11H3z" />,
  chevron: <path d="M9 5l7 7-7 7" />,
  back: <path d="M15 5l-7 7 7 7" />,
  plus: <path d="M12 5v14M5 12h14" />,
  camera: (
    <>
      <path d="M3 8h4l2-3h6l2 3h4v11H3z" />
      <polygon points="10,10 14,10 16,13 14,16 10,16 8,13" />
    </>
  ),
  image: (
    <>
      <path d="M3 5h18v14H3z" />
      <path d="M3 16l5-5 4 4 3-3 6 6" />
    </>
  ),
  file: (
    <>
      <path d="M6 3h8l4 4v14H6z" />
      <path d="M14 3v4h4" />
    </>
  ),
  filter: <path d="M3 5h18l-7 8v6l-4 2v-8z" />,
  chat: (
    <>
      <path d="M3 4h18v12H11l-5 4v-4H3z" />
      <path d="M7 9h10M7 12h6" />
    </>
  ),
} as const;

export type IconName = keyof typeof PATHS;

export function Icon({ name, className = "h-5 w-5" }: { name: IconName; className?: string }) {
  return (
    <svg
      viewBox="0 0 24 24"
      className={`flex-none ${className}`}
      fill="none"
      stroke="currentColor"
      strokeWidth="1.8"
      strokeLinecap="square"
      aria-hidden="true"
      focusable="false"
    >
      {PATHS[name]}
    </svg>
  );
}

/** The small diamond that marks a state; its colour comes from the text colour around it. */
export function Diamond({ className = "h-2 w-2" }: { className?: string }) {
  return <span aria-hidden="true" className={`inline-block flex-none bg-current [clip-path:polygon(50%_0,100%_50%,50%_100%,0_50%)] ${className}`} />;
}
