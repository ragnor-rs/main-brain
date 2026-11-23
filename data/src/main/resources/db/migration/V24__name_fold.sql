-- The comparable form of a name, so that resolving a subject can cross a script.
--
-- The corpus is Russian and the interface answers in Russian, so a reader writing a question writes
-- a name the way their own question writes it — whatever script the source happened to store the
-- display name in. A label is copied from the source and never transliterated, so the two forms
-- share no characters and no comparison on the stored strings can bring them together. Folding both
-- sides to one form is what makes the question answerable.
--
-- **Defined here and nowhere else.** The comparison applies to the stored label as much as to the
-- typed name, so it has to run where the labels are; a second copy of the table in another language
-- would have to agree with this one character for character to match anything at all, and the two
-- drifting apart is a failure that looks like a name nobody carries. The read folds the typed name
-- by calling this, rather than by reimplementing it.
--
-- The steps, in order, every one of them applied to both sides:
--
--   * Lowercased. This leans on the database knowing the corpus's script, which a `C`-ctype cluster
--     does not: there the Cyrillic would survive as upper case, fall through the letter step and be
--     spaced out by the last one. Every cluster this runs on inherits a UTF-8 ctype from the pinned
--     image, so the dependency is recorded here rather than guarded against.
--   * A soft or hard sign becomes an i-glide before any of the five vowels that are themselves
--     written with one, because that is what the Latin renderings of such a name write. Everywhere
--     else — before a consonant, and at the end of a word — it is dropped, where they write nothing.
--   * The letters that render as several Latin ones: zh, ch, sh, shch, ts, yu, ya, h, and e for the
--     vowel written with a diaeresis.
--   * The letters that render as one.
--   * The renderings that disagree about the same letter, each folded onto the form the step above
--     produces: kh for the letter this writes h, and yo or jo for the vowel it writes e. Without
--     these the commonest English rendering of a Russian name — the one a passport uses — fails to
--     meet the Cyrillic it came from, which is the whole point of the fold.
--   * The variants that render the same sound differently — x for ks, and y and j for the i-glide,
--     which is what makes a name ending -iy, -iya or -ey meet the same name ending -i, -ia or -ei.
--     The glide is folded and not deleted: a word-initial e written ye keeps its glide, because
--     deleting it would stop a name ending -ey meeting the same name ending -ei.
--   * A run of one letter collapses to that letter, which is the other half of the same ending and
--     also brings a doubled Latin consonant to the single Cyrillic one.
--   * Everything that is not a letter, a digit or the handle marker becomes a single space, and the
--     result is trimmed. That settles two things beside punctuation: a run of whitespace collapses,
--     and a typed name can carry no `LIKE` wildcard, since `%`, `_` and `\` all fold to a space.
--
-- **The marker survives, and is the one character that does.** It is how a reader says they mean a
-- handle rather than a label. A folded label contains one only where the label itself did, so for
-- every label that holds no marker — which is all of them in this corpus — a name typed with one
-- reaches no label.
--
-- **This is the comparison for a label and never for a handle.** A handle is the source's own key:
-- it is written in one script, it is what a reader names to pick one of two subjects that share a
-- label, and so the thing it must keep is exactness. Folding it would make two distinct handles one
-- string and turn its separator into a word break, which is the opposite of what the fourth branch
-- of the read exists for.
--
-- What this cannot do is cross a name form that is not a transliteration at all: where a label holds
-- the English form of a given name, the Cyrillic of it folds to the transliteration and the two do
-- not meet. Such a subject resolves by the surname beside it. The cost of the steps that reduce is
-- paid in the other direction — two names that differ only by a doubled letter, or by a glide, fold
-- together and are offered as two readings of one name.
--
-- **Recognition compares a display name too, and not with this.** Its name key is whole-string
-- equality after trim, collapse and lowercase, indexed that way, because it asks whether two
-- accounts are one person and a near-miss there is a wrong merge. This asks what a reader meant by
-- what they typed, where a near-miss is the answer. The two are deliberately separate rules over
-- separate columns; neither reads the other's.
--
-- Immutable because every function it calls is. What that buys is the folding of a call whose
-- argument is a parameter rather than a column, which is how the read folds the typed name, and
-- index-ability if a label count ever made an index worth having — not any saving on a column
-- argument, which is evaluated per row whatever the volatility.
CREATE FUNCTION name_fold(label text) RETURNS text
LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE
AS $$
    WITH lowered AS (SELECT lower(label) AS s),
         glided AS (SELECT regexp_replace(s, '[ьъ]([еёюяи])', 'и\1', 'g') AS s FROM lowered),
         digraphs AS (
             SELECT replace(replace(replace(replace(replace(replace(replace(replace(replace(
                        s, 'ж', 'zh'), 'ч', 'ch'), 'ш', 'sh'), 'щ', 'shch'),
                        'х', 'h'), 'ц', 'ts'), 'ю', 'yu'), 'я', 'ya'), 'ё', 'e') AS s
             FROM glided
         ),
         letters AS (
             SELECT translate(s, 'абвгдезийклмнопрстуфыэьъ', 'abvgdeziyklmnoprstufye') AS s
             FROM digraphs
         ),
         renderings AS (
             SELECT replace(replace(replace(s, 'kh', 'h'), 'yo', 'e'), 'jo', 'e') AS s
             FROM letters
         ),
         variants AS (SELECT translate(replace(s, 'x', 'ks'), 'yj', 'ii') AS s FROM renderings),
         collapsed AS (SELECT regexp_replace(s, '([a-z])\1+', '\1', 'g') AS s FROM variants)
    SELECT btrim(regexp_replace(s, '[^a-z0-9@]+', ' ', 'g')) FROM collapsed
$$;
