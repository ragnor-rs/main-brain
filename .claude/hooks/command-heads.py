"""Print the command heads of a shell command line, one per line.

Both hooks under `.claude/hooks/` judge what a Bash call *runs*, never what it says, and
that distinction cannot be made with a line-based pattern. A pull-request comment narrating
a command, a heredoc carrying a test plan, a document quoting a gate — each contains the
text of a command and runs none of it, while `bash -c "gh pr merge"` contains it inside
quotes and runs it. Getting that wrong in either direction is a defect: a guard that refuses
honest work is a guard somebody turns off, and a guard a wrapper walks past enforces nothing.

So the line is tokenised: quotes are respected, heredoc bodies are dropped, the text is split
on unquoted separators, and each resulting command is reduced to its head — the name it runs
and its arguments, with leading environment assignments, wrappers and loop keywords removed.
A `-c` payload, an `eval` argument and a `bash some.sh` script path are themselves commands,
so they are expanded too.

Reads the command on stdin. Prints one head per line, tokens separated by single spaces and
unquoted, so a caller can match on positions without parsing anything itself. Prints nothing
for text that runs nothing. Exits non-zero only if stdin cannot be read; unparseable input —
an unbalanced quote, most often from prose — yields what could be read rather than an error,
because the caller's fallback is cruder than this.
"""

import re
import shlex
import sys

# Characters that end one command and begin another. A newline is one of them and has to be
# declared as punctuation, because shlex otherwise treats it as ordinary whitespace and two
# commands on two lines become one head — while a newline *inside* quotes still belongs to the
# quoted word, which is what keeps a multi-line comment body from being read as commands.
PUNCTUATION = "();<>|&\n"
SEPARATOR_CHARACTERS = {";", "&", "|", "\n"}
# Wrappers that run the command that follows them. `timeout` is here with its own handling:
# it takes a duration, and options before it.
WRAPPERS = {"sudo", "time", "env", "nohup", "command", "builtin", "exec", "stdbuf", "nice"}
# Shell grammar that can stand where a command name does.
KEYWORDS = {
    "do", "done", "then", "else", "elif", "fi", "if", "while", "until", "for", "case",
    "esac", "in", "{", "}", "(", ")", "!", "function", "select",
}
SHELLS = {"bash", "sh", "zsh", "dash", "ksh"}
ASSIGNMENT = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*=")
# A heredoc operator: `<<` or `<<-`, a delimiter word, and the body starting on the next
# line. `<<<` is a here-string and carries no body; `$((1 << 3))` is a shift, which is why
# the delimiter has to look like a word.
HEREDOC = re.compile(r"<<-?\s*(['\"]?)([A-Za-z_][A-Za-z0-9_]*)\1")
MAX_DEPTH = 3


def strip_heredoc_bodies(text):
    """Drop every heredoc body, keeping the line that opened it and everything after the
    terminator. A body is data: `cat <<EOF` followed by a line naming a deploy script runs
    no deploy, and the commands after the terminator are still commands."""
    out = []
    lines = text.split("\n")
    i = 0
    while i < len(lines):
        line = lines[i]
        out.append(line)
        delimiters = [m.group(2) for m in HEREDOC.finditer(line.replace("<<<", "   "))]
        i += 1
        for delimiter in delimiters:
            while i < len(lines) and lines[i].strip() != delimiter:
                i += 1
            i += 1  # the terminator line itself
    return "\n".join(out)


def tokenise(text):
    lexer = shlex.shlex(text, posix=True, punctuation_chars=PUNCTUATION)
    lexer.whitespace_split = True
    # Declaring the newline punctuation is not enough on its own: shlex checks whitespace
    # first, so it would be swallowed and `a\nb` would come back as one command.
    lexer.whitespace = lexer.whitespace.replace("\n", "")
    tokens = []
    while True:
        try:
            token = lexer.get_token()
        except ValueError:
            break  # an unbalanced quote: keep what was read
        if token is lexer.eof or token is None:
            break
        tokens.append(token)
    return tokens


def split_commands(tokens):
    """Split on separator tokens. Runs of punctuation arrive grouped — `&&`, `||`, `;;`, a
    newline, several newlines — so a token is a separator when it is made of nothing else.
    `>&` is not: a redirection stays with the command it belongs to."""
    commands, current = [], []
    for token in tokens:
        if token and set(token) <= SEPARATOR_CHARACTERS:
            if current:
                commands.append(current)
            current = []
        else:
            current.append(token)
    if current:
        commands.append(current)
    return commands


def reduce_head(words):
    """Strip what stands in front of the command name, until a name is what is left."""
    while words:
        first = words[0]
        if ASSIGNMENT.match(first) or first in WRAPPERS or first in KEYWORDS:
            words = words[1:]
            continue
        if first == "timeout":
            # `timeout -k 5 60 cmd`: options, an option's own duration, then the duration.
            words = words[1:]
            while words and (words[0].startswith("-") or re.match(r"^[0-9.]+[smhd]?$", words[0])):
                words = words[1:]
            continue
        break
    return words


def nested(words):
    """The commands a command carries: a `-c` payload, an `eval` argument, a script path."""
    if not words:
        return []
    name = words[0].rsplit("/", 1)[-1]
    if name == "eval":
        return [" ".join(words[1:])] if len(words) > 1 else []
    if name in SHELLS:
        rest = words[1:]
        for index, word in enumerate(rest):
            if word == "-c" and index + 1 < len(rest):
                return [rest[index + 1]]
        for word in rest:
            if not word.startswith("-"):
                # `bash -x scripts/deploy.sh --env dev` runs the deploy script.
                return [" ".join([word] + rest[rest.index(word) + 1:])]
    return []


def heads_of(text, depth=0):
    if depth > MAX_DEPTH:
        return []
    found = []
    for words in split_commands(tokenise(strip_heredoc_bodies(text))):
        words = reduce_head(words)
        if not words:
            continue
        found.append(" ".join(words))
        for inner in nested(words):
            found.extend(heads_of(inner, depth + 1))
    return found


def main():
    text = sys.stdin.read()
    for head in heads_of(text):
        # One head per line, whitespace collapsed: a quoted argument may carry newlines of
        # its own, and a caller reading line by line must not see them as separate commands.
        collapsed = " ".join(head.split())
        if collapsed:
            print(collapsed)


if __name__ == "__main__":
    main()
