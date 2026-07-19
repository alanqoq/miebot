import {
  DEFAULT_GATEWAY_INTENTS,
  GATEWAY_INTENT_OPTIONS,
  KNOWN_GATEWAY_INTENTS,
  hasGatewayIntent,
  setGatewayIntent,
  unknownGatewayIntents,
} from './gateway-intents';

describe('gateway intents', () => {
  it('uses the official group and C2C event bit by default', () => {
    expect(DEFAULT_GATEWAY_INTENTS).toBe(33_554_432);
    expect(GATEWAY_INTENT_OPTIONS.find((option) => option.key === 'GROUP_AND_C2C_EVENT')?.bit).toBe(
      DEFAULT_GATEWAY_INTENTS,
    );
  });

  it('defines unique official intent bits', () => {
    const bits = GATEWAY_INTENT_OPTIONS.map((option) => option.bit);

    expect(new Set(bits).size).toBe(bits.length);
    expect(KNOWN_GATEWAY_INTENTS).toBe(bits.reduce((mask, bit) => mask + bit, 0));
  });

  it('adds and removes known bits without changing unknown bits', () => {
    const unknownBit = 2 ** 40;
    const initial = unknownBit + DEFAULT_GATEWAY_INTENTS;
    const withoutDefault = setGatewayIntent(initial, DEFAULT_GATEWAY_INTENTS, false);
    const withInteraction = setGatewayIntent(withoutDefault, 2 ** 26, true);

    expect(withoutDefault).toBe(unknownBit);
    expect(hasGatewayIntent(withInteraction, 2 ** 26)).toBe(true);
    expect(unknownGatewayIntents(withInteraction)).toBe(unknownBit);
  });
});
