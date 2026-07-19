export interface GatewayIntentOption {
  key: string;
  label: string;
  bit: number;
  privateOnly?: boolean;
}

export const DEFAULT_GATEWAY_INTENTS = 2 ** 25;

export const GATEWAY_INTENT_OPTIONS: readonly GatewayIntentOption[] = [
  { key: 'GROUP_AND_C2C_EVENT', label: '群聊与单聊消息', bit: 2 ** 25 },
  { key: 'PUBLIC_GUILD_MESSAGES', label: '公域频道 @ 消息', bit: 2 ** 30 },
  { key: 'DIRECT_MESSAGE', label: '频道私信', bit: 2 ** 12 },
  { key: 'GUILD_MESSAGES', label: '私域频道全部消息', bit: 2 ** 9, privateOnly: true },
  { key: 'GUILD_MESSAGE_REACTIONS', label: '频道消息表情回应', bit: 2 ** 10 },
  { key: 'GUILDS', label: '频道与子频道变更', bit: 2 ** 0 },
  { key: 'GUILD_MEMBERS', label: '频道成员变更', bit: 2 ** 1 },
  { key: 'INTERACTION', label: '互动事件', bit: 2 ** 26 },
  { key: 'MESSAGE_AUDIT', label: '消息审核事件', bit: 2 ** 27 },
  { key: 'FORUMS_EVENT', label: '论坛事件', bit: 2 ** 28, privateOnly: true },
  { key: 'AUDIO_ACTION', label: '音频事件', bit: 2 ** 29 },
];

export const KNOWN_GATEWAY_INTENTS = GATEWAY_INTENT_OPTIONS.reduce(
  (mask, option) => mask + option.bit,
  0,
);

export function hasGatewayIntent(mask: number, bit: number): boolean {
  requireSafeMask(mask);
  requireIntentBit(bit);
  return Math.floor(mask / bit) % 2 === 1;
}

export function setGatewayIntent(mask: number, bit: number, selected: boolean): number {
  const currentlySelected = hasGatewayIntent(mask, bit);
  if (currentlySelected === selected) {
    return mask;
  }
  const next = mask + (selected ? bit : -bit);
  requireSafeMask(next);
  return next;
}

export function unknownGatewayIntents(mask: number): number {
  requireSafeMask(mask);
  return GATEWAY_INTENT_OPTIONS.reduce(
    (unknown, option) => (hasGatewayIntent(unknown, option.bit) ? unknown - option.bit : unknown),
    mask,
  );
}

function requireSafeMask(mask: number): void {
  if (!Number.isSafeInteger(mask) || mask < 0) {
    throw new RangeError('Gateway intents must be a non-negative safe integer');
  }
}

function requireIntentBit(bit: number): void {
  if (!Number.isSafeInteger(bit) || bit <= 0 || Math.log2(bit) % 1 !== 0) {
    throw new RangeError('Gateway intent bit must be a positive power of two');
  }
}
