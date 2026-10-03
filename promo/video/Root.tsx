import { Composition } from "remotion";
import { Calm, CALM_FRAMES } from "./Calm";
import { Promo, PROMO_FRAMES } from "./Promo";
import { V11, V11_FRAMES } from "./V11";

export const Root = () => (
  <>
    <Composition id="Promo" component={Promo} durationInFrames={PROMO_FRAMES} fps={30} width={1920} height={1080} />
    <Composition id="V11" component={V11} durationInFrames={V11_FRAMES} fps={30} width={1920} height={1080} />
    <Composition id="Calm" component={Calm} durationInFrames={CALM_FRAMES} fps={30} width={1920} height={1080} />
  </>
);
