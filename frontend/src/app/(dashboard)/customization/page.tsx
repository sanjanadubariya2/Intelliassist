import { redirect } from "next/navigation";

/** Customization now lives in Widget Studio. Kept so old links still land. */
export default function CustomizationPage() {
  redirect("/studio");
}
