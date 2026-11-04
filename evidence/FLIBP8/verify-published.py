from pathlib import Path
import hashlib,json,struct,sys,urllib.request,zipfile,xml.etree.ElementTree as E
version='1.3.0-codexcoder21-snapshot-45'
coordinate='community.kotlin.libp2p:jvm-libp2p:'+version
stage=Path('/tmp/flibp8-publish45')
out=Path('/tmp/flibp8-downloaded45');out.mkdir(exist_ok=True)
evidence=Path(__file__).resolve().parent
base='https://kotlin.directory/community/kotlin/libp2p/jvm-libp2p/'+version+'/'
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def zip_without_dos_times(path):
    data=bytearray(path.read_bytes())
    with zipfile.ZipFile(path) as z:
        offset=z.start_dir
        for info in z.infolist():
            local=info.header_offset
            assert data[local:local+4]==b'PK\x03\x04'
            data[local+10:local+14]=bytes(4)
            assert data[offset:offset+4]==b'PK\x01\x02'
            data[offset+12:offset+16]=bytes(4)
            name_len,extra_len,comment_len=struct.unpack_from('<HHH',data,offset+28)
            offset+=46+name_len+extra_len+comment_len
    return bytes(data)
for ext in ['jar','pom']:
    name='jvm-libp2p-'+version+'.'+ext
    with urllib.request.urlopen(base+name,timeout=30) as response:
        assert response.status==200
        (out/name).write_bytes(response.read())
jar_name='jvm-libp2p-'+version+'.jar';pom_name='jvm-libp2p-'+version+'.pom'
with zipfile.ZipFile(stage/jar_name) as a,zipfile.ZipFile(out/jar_name) as b:
    assert a.namelist()==b.namelist(),'JAR entry lists differ'
    names=a.namelist()
    assert len(names)==len(set(names)),'Duplicate JAR entry names'
    different=[name for name in names if a.read(name)!=b.read(name)]
    assert not different,('JAR entry bytes differ',different)
    entries={name:hashlib.sha256(a.read(name)).hexdigest() for name in names}
assert (stage/pom_name).read_bytes()==(out/pom_name).read_bytes(),'POM bytes differ'
ns={'m':'http://maven.apache.org/POM/4.0.0'};pom=E.parse(out/pom_name)
assert [pom.findtext('m:'+n,namespaces=ns) for n in ['groupId','artifactId','version']]==['community.kotlin.libp2p','jvm-libp2p',version]
identical=sha(stage/jar_name)==sha(out/jar_name)
assert identical or zip_without_dos_times(stage/jar_name)==zip_without_dos_times(out/jar_name),'Raw differences extend beyond ZIP DOS timestamps'
result={'coordinate':coordinate,'commit':'e3e5caf4a0d5cfc1acd40be2ec7468078ce9f8fe','local_jar_sha256':sha(stage/jar_name),'downloaded_jar_sha256':sha(out/jar_name),'local_pom_sha256':sha(stage/pom_name),'downloaded_pom_sha256':sha(out/pom_name),'jar_entries':len(names),'class_entries':sum(n.endswith('.class') for n in names),'different_entries':different,'pom_identical':True,'raw_jars_identical':identical,'verified':True,'jar_url':base+jar_name,'pom_url':base+pom_name}
(evidence/'published-verification.json').write_text(json.dumps(result,indent=2)+'\n')
(evidence/'published-entry-hashes.json').write_text(json.dumps(entries,indent=2,sort_keys=True)+'\n')
print(json.dumps(result,indent=2))
