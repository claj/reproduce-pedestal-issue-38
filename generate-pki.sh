#/bin/bash

# terminate on errors
set -e

export CERT_NAME=$1
export CERT_PASSWORD_BYTES=36
export DNS_ENTRY=repro.local
mkdir -p config/"$CERT_NAME"
cd config/"$CERT_NAME"

echo "using step-cli version ..."
step --version || exit ERRCODE "You need to have step-cli installed, see https://smallstep.com/cli/ or try \n\n brew install step"


################################################################
echo Create root key/cert...

# password file
openssl rand -base64 $CERT_PASSWORD_BYTES -out ca.password

# step certificate create subject crt-file key-file

step certificate create \
     --force \
     --profile root-ca \
     "Repro $CERT_NAME Root CA" \
     "$CERT_NAME"_root_ca.crt \
     "$CERT_NAME"_root_ca.key \
     --password-file=ca.password


################################################################
echo Create intermediate certificate - used for signing of leaf certs.

#password file
openssl rand -base64 $CERT_PASSWORD_BYTES -out intermediate.password

step certificate create \
     --force \
     --profile intermediate-ca \
     "Repro $CERT_NAME Intermediate CA" \
     "$CERT_NAME"_intermediate_ca.crt \
     "$CERT_NAME"_intermediate_ca.key \
     --ca ./"$CERT_NAME"_root_ca.crt \
     --ca-key ./"$CERT_NAME"_root_ca.key \
     --ca-password-file=ca.password \
     --password-file=intermediate.password

################################################################
# create Truststore
echo Create jetty-trust.p12

openssl rand -base64 $CERT_PASSWORD_BYTES -out jetty-trust.password

step certificate p12 jetty-trust.p12 \
     --force \
     --ca "$CERT_NAME"_root_ca.crt \
     --password-file jetty-trust.password

# should we add intermediate as well? --ca "$CERT_NAME"_intermediate_ca.crt \

################################################################
echo Create leaf certificate for server
# no password - because we want to convert it to p12.

step certificate create $DNS_ENTRY "$DNS_ENTRY".crt "$DNS_ENTRY".key \
     --profile leaf \
     --force \
     --not-after=8760h \
     --ca ./"$CERT_NAME"_intermediate_ca.crt \
     --ca-key ./"$CERT_NAME"_intermediate_ca.key \
     --ca-password-file=intermediate.password \
     --insecure \
     --no-password \
     --bundle

# this creates a valid certificate for example.com that can be loaded into jetty
openssl rand -base64 $CERT_PASSWORD_BYTES -out jetty-keystore.password

step certificate p12 jetty-keystore.p12 "$DNS_ENTRY".crt "$DNS_ENTRY".key --password-file=jetty-keystore.password --force

################################################################
echo Create client certificate
# intermediate certificate as above

step certificate create ReproClient clientcert.crt clientcert.key \
     --profile leaf \
     --not-after=8760h \
     --force \
     --insecure \
     --no-password \
     --ca ./"$CERT_NAME"_intermediate_ca.crt \
     --ca-key ./"$CERT_NAME"_intermediate_ca.key \
     --ca-password-file=intermediate.password \
     --bundle

openssl rand -base64 $CERT_PASSWORD_BYTES -out clientcert.password
echo "converting client cert to p12..."
step certificate p12 clientcert.p12 clientcert.crt clientcert.key --password-file=clientcert.password --force
cd ../..
echo "done."
echo

echo config/"$CERT_NAME"/jetty-keystore.p12
echo config/"$CERT_NAME"/jetty-trust.p12
echo config/"$CERT_NAME"/clientcert.p12
