"""Read the checked-out Kotlin declarations; fail closed on unsupported syntax."""

import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]
BASE = Path('app/src/main/java/com/alrasin/jarvis')


def split_args(text):
    parts, start, depth, quoted, escaped = [], 0, 0, False, False
    for i, char in enumerate(text):
        if quoted:
            if escaped:
                escaped = False
            elif char == '\\':
                escaped = True
            elif char == '"':
                quoted = False
        elif char == '"':
            quoted = True
        elif char in '([{':
            depth += 1
        elif char in ')]}':
            depth -= 1
        elif char == ',' and depth == 0:
            parts.append(text[start:i].strip())
            start = i + 1
    if quoted or depth:
        raise ValueError('Unbalanced Kotlin declaration')
    return [p for p in parts + [text[start:].strip()] if p]


def balanced(text, start):
    depth, quoted, escaped = 1, False, False
    for i in range(start, len(text)):
        c = text[i]
        if quoted:
            if escaped:
                escaped = False
            elif c == '\\':
                escaped = True
            elif c == '"':
                quoted = False
        elif c == '"':
            quoted = True
        elif c == '(':
            depth += 1
        elif c == ')':
            depth -= 1
            if not depth:
                return text[start:i]
    raise ValueError('Unclosed Kotlin declaration')


def literal(value):
    result = json.loads(value)
    if not isinstance(result, str) or '$' in result or '<' in result:
        raise ValueError('Expected literal Kotlin string')
    return result


def load_contract(root=ROOT):
    root = Path(root)
    registry = (root / BASE / 'actions/ToolRegistry.kt').read_text()
    sources = {
        'appTools': 'actions/AppTools.kt', 'timeTools': 'actions/TimeTools.kt',
        'commsTools': 'actions/CommsTools.kt', 'mediaTools': 'actions/MediaTools.kt',
        'systemTools': 'actions/SystemTools.kt', 'unlockTool': 'actions/SystemTools.kt',
        'screenTools': 'actions/ScreenTools.kt', 'dataTools': 'actions/DataTools.kt',
        'notificationTools': 'notify/NotificationTools.kt', 'webTools': 'actions/WebTools.kt',
    }
    registered = re.findall(r'add(?:All)?\((\w+)\(', registry)
    if set(registered) != set(sources):
        raise ValueError('Registry changed: review Kotlin extraction')
    definitions = {}
    for path in dict.fromkeys(sources.values()):
        source = (root / BASE / path).read_text()
        constants = dict(re.findall(r'val (\w+) = (listOf\([^\n]+\))', source))
        for match in re.finditer(r'\bSimpleTool\(', source):
            fields = split_args(balanced(source, match.end()))
            name, description = map(literal, fields[:2])
            args = []
            for field in fields[2:]:
                if not field.startswith('listOf('):
                    continue
                for declaration in split_args(field[7:-1]):
                    if not declaration.startswith('arg('):
                        raise ValueError('Unsupported argument syntax')
                    values = split_args(declaration[4:-1])
                    arg_name, desc = map(literal, values[:2])
                    required, choices = True, None
                    for index, value in enumerate(values[2:], 2):
                        if '=' in value:
                            key, value = map(str.strip, value.split('=', 1))
                        else:
                            key = 'required' if index == 2 else 'choices'
                        if key == 'required':
                            if value not in ('true', 'false'):
                                raise ValueError('Unsupported required flag')
                            required = value == 'true'
                        elif key == 'choices':
                            value = constants.get(value, value)
                            if not value.startswith('listOf('):
                                raise ValueError('Unsupported choices')
                            choices = [literal(v) for v in split_args(value[7:-1])]
                        else:
                            raise ValueError('Unsupported argument field')
                    args.append((arg_name, desc, required, choices))
            if name in definitions:
                raise ValueError('Duplicate tool')
            definitions[name] = (description, args, path)
    ordered = []
    for function in registered:
        for name, (description, args, path) in definitions.items():
            if path == sources[function] and ((name == 'unlock_phone') == (function == 'unlockTool')):
                ordered.append((name, description, args))
    declarations, schemas = [], {}
    esc = '<escape>'
    for name, description, args in ordered:
        props = []
        for key, desc, required, choices in args:
            enum = ',enum:[' + ','.join(esc + c + esc for c in choices) + ']' if choices else ''
            props.append(f'{key}:{{description:{esc}{desc}{esc}{enum},type:{esc}STRING{esc}}}')
        required = [key for key, _, req, _ in args if req]
        req = ','.join(esc + key + esc for key in required)
        declarations.append(f'<start_function_declaration>declaration:{name}{{description:{esc}{description}{esc},parameters:{{properties:{{' + ','.join(props) + f'}},required:[{req}],type:{esc}OBJECT{esc}}}}}<end_function_declaration>')
        schemas[name] = ([a[0] for a in args], required, {a[0]: a[3] for a in args if a[3]})
    prefix = '<bos><start_of_turn>developer\nYou are a model that can do function calling with the following functions' + ''.join(declarations) + '<end_of_turn>\n'
    return prefix, schemas


def validate_call(tool, args, schemas):
    if tool is None or tool == 'chat':
        if args != {}:
            raise ValueError('No-call targets cannot have arguments')
        return
    if tool not in schemas or not isinstance(args, dict):
        raise ValueError('Unknown tool or invalid arguments')
    order, required, choices = schemas[tool]
    if set(args) - set(order) or set(required) - set(args):
        raise ValueError('Unknown or missing argument')
    for key, value in args.items():
        if not isinstance(value, str) or not value.strip() or '<' in value or '\x00' in value:
            raise ValueError('Invalid argument string')
        if key in choices and value not in choices[key]:
            raise ValueError('Invalid enum value')
