import { redirect } from "next/navigation";

/** API keys and the embed snippet now live in Widget Studio's Install tab. */
export default function EmbedPage() {
  redirect("/studio?tab=install");
}
