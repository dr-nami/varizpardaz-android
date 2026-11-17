// Contract section 1, for the mock server only (the real server has its own implementation; both are
// checked against fixtures/normalise.json).
const BIDI = /[‎‏‪-‮⁦-⁩]/g;

export function normalise(text) {
  return String(text)
    .replace(/[۰-۹]/g, (c) => String(c.charCodeAt(0) - 0x06f0))
    .replace(/[٠-٩]/g, (c) => String(c.charCodeAt(0) - 0x0660))
    .replace(/ي/g, 'ی')
    .replace(/ك/g, 'ک')
    .replace(BIDI, '')
    .replace(/[٬،]/g, ',')
    .replace(/\s+/g, ' ')
    .trim();
}

/** Sender gate normalisation (section 6, step 1): spaces and one leading `+` removed. */
export const normaliseSender = (s) => String(s).replace(/\s+/g, '').replace(/^\+/, '');
