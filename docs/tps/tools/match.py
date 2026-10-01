"""Find the 7.1 message that matches a 7.0 message by structure.

usage: match.py OLD.proto NEW.proto NEW_NAMES.json NAME [NAME...]

Obfuscated names change every version, so messages are compared by shape: the multiset of
(label, kind) of their fields. kind is the scalar type, map<k,v>, the type's real name when both
dumps know it (SceneWeaponInfo, Uint32Pair, ...), or else the shape of the referenced type.

Matching is constraint propagation over the NAMEs given together:
  1. a NAME the translation already knows is taken as is; otherwise the candidates are the new
     messages with the same shape;
  2. a NAME with exactly one candidate is resolved, and the types of its fields resolve the
     matching child types (leaf structs - three uint32s look like a hundred others - are pinned
     down this way, through the message that carries them);
  3. inside one version an obfuscated field name always stands for the same real name, so each
     resolved pair teaches an old->new field-name map (avatar_guid -> ECMNNFKNAIK, ...); every
     candidate must use the mapped names, must carry the already-resolved child types, and may not
     already be taken by another NAME;
  4. repeat until nothing changes. What is left ambiguous is printed ranked by field numbers that
     line up (nested structs usually keep them; packets usually don't) and by CmdId presence, with
     "evidence": other named messages that use the candidate's field names.
"""
import json, os, re, sys
sys.path.insert(0, os.path.dirname(__file__))
import protoparse as p

OBF = re.compile(r'^[A-P]{11}$')

def real_name(name, nt):
    n = nt.get(name, name)
    return None if OBF.match(n) else n.lstrip('_')

def shape(msgs, m, nt, shared, depth=2):
    if m.kind == 'enum':
        return ('enum', len(m.values))
    out = []
    for f in m.fields:
        t = p.resolve(msgs, m, f.type)
        if t in msgs:
            rn = real_name(msgs[t].name, nt)
            if rn in shared:
                kind = ('named', rn)
            elif depth > 0:
                kind = shape(msgs, msgs[t], nt, shared, depth - 1)
            else:
                kind = msgs[t].kind
        else:
            kind = t
        out.append((f.label, bool(f.oneof), kind))
    return tuple(sorted(out, key=repr))

def numbers(m):
    return {(f.label, f.type if f.type in p.SCALARS or f.type.startswith('map<') else '*', f.num)
            for f in m.fields}

def field_names(msgs, m, depth=1):
    names = {f.name for f in m.fields}
    if depth > 0:
        for f in m.fields:
            t = p.resolve(msgs, m, f.type)
            if t in msgs: names |= field_names(msgs, msgs[t], depth - 1)
    return names

def evidence(new, nt, m1):
    hits = []
    for f in m1.fields:
        for other in new.values():
            if other is m1: continue
            for g in other.fields:
                if g.name == f.name and real_name(other.name, nt):
                    hits.append(f'{f.name}~{real_name(other.name, nt)}.{g.num}')
    return hits

if __name__ == '__main__':
    old, new = p.parse(sys.argv[1]), p.parse(sys.argv[2])
    nt = json.load(open(sys.argv[3]))
    shared = {real_name(m.name, {}) for m in old.values()} & {real_name(m.name, nt) for m in new.values()}
    shared.discard(None)
    new_shapes = {k: shape(new, m, nt, shared) for k, m in new.items()}
    freq = {}
    for m in new.values():
        for f in m.fields: freq[f.name] = freq.get(f.name, 0) + 1
    rare = lambda ns: {n for n in ns if freq.get(n, 0) <= 4}

    names = sys.argv[4:]
    cands, why = {}, {}
    for name in names:
        m0 = old[name]
        s0 = shape(old, m0, {}, shared)
        named = [m1 for m1 in new.values() if real_name(m1.name, nt) == name.lstrip('_')]
        if len(named) == 1:
            cands[name], why[name] = named, 'name known to the translation'
            continue
        cands[name] = [m1 for k, m1 in new.items() if m1.kind == m0.kind and new_shapes[k] == s0]
        why[name] = 'shape'

    fieldmap = {}   # old field name -> new field name, learned from resolved pairs
    # Seed it with field names the new translation already knows (affix_map -> DJLJCHOPHHC).
    by_real = {}
    for obf, real in nt.items():
        if real[:1].islower(): by_real.setdefault(real, set()).add(obf)
    fieldmap.update({real: next(iter(o)) for real, o in by_real.items() if len(o) == 1})

    def fkey(msgs, shapes_of, m, f):
        t = p.resolve(msgs, m, f.type)
        return (f.label, shapes_of(msgs[t]) if t in msgs else t)

    old_shape = lambda m: shape(old, m, {}, shared) if m.kind == 'message' else ('enum', len(m.values))
    new_shape = lambda m: new_shapes[m.full] if m.kind == 'message' else ('enum', len(m.values))

    def learn(m0, m1):
        k0 = [fkey(old, old_shape, m0, f) for f in m0.fields]
        k1 = [fkey(new, new_shape, m1, g) for g in m1.fields]
        for f, k in zip(m0.fields, k0):
            if k0.count(k) == 1 and k1.count(k) == 1:
                fieldmap.setdefault(f.name, m1.fields[k1.index(k)].name)

    def resolved_type(t0):
        c = cands.get(t0)
        return c[0].full if c and len(c) == 1 else None

    def consistent(m0, m1):
        names1 = {g.name for g in m1.fields}
        types1 = {p.resolve(new, m1, g.type) for g in m1.fields}
        for f in m0.fields:
            if f.name in fieldmap and fieldmap[f.name] not in names1: return False
            t1 = resolved_type(p.resolve(old, m0, f.type))
            if t1 and t1 not in types1: return False
        return True

    changed = True
    while changed:
        changed = False
        taken = {c[0].full: n for n, c in cands.items() if len(c) == 1}
        for name in names:
            if len(cands[name]) == 1:
                m0, m1 = old[name], cands[name][0]
                before = len(fieldmap); learn(m0, m1); changed |= len(fieldmap) != before
                # Rule 2: the resolved message's field types resolve its child types.
                for f in m0.fields:
                    t0 = p.resolve(old, m0, f.type)
                    if t0 not in cands or len(cands[t0]) == 1: continue
                    k = fkey(old, old_shape, m0, f)
                    kids = [new[p.resolve(new, m1, g.type)] for g in m1.fields
                            if fkey(new, new_shape, m1, g) == k and p.resolve(new, m1, g.type) in new]
                    kids = [c for c in kids if c in cands[t0]]
                    if len(kids) == 1:
                        cands[t0], why[t0], changed = kids, f'type of {name}.{f.name}', True
                continue
            # Rule 3: field names and resolved child types must line up; one new message per old one.
            keep = [c for c in cands[name] if c.full not in taken and consistent(old[name], c)]
            if keep and len(keep) < len(cands[name]):
                cands[name], why[name], changed = keep, 'field names / child types', True

    for name in names:
        m0 = old[name]
        res = sorted(cands[name], key=lambda m1: -(len(numbers(m0) & numbers(m1)) + (2 if bool(m0.cmd) == bool(m1.cmd) else 0)))
        tag = 'RESOLVED' if len(res) == 1 else f'{len(res)} candidates'
        print(f'{name} (7.0 cmd {m0.cmd}): {tag} [{why[name]}]')
        for m in res[:6]:
            ev = evidence(new, nt, m)
            print(f'   {m.full}  cmd={m.cmd}  {m.fields or m.values}'
                  + (f'\n            evidence: {", ".join(ev[:6])}' if ev else ''))
