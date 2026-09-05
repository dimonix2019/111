# CA bundle for T‑Invest REST (OpenSSL / requests).
#
# T‑Bank public API presents a leaf under «Russian Trusted Sub CA» /
# «Russian Trusted Root CA» (Минцифры). Mozilla's certifi cacert.pem does not
# include that root, so vanilla requests fails with:
#   SSLCertVerificationError: self-signed certificate in certificate chain
#
# Sources (AIA from invest-public-api.tinkoff.ru leaf, 2026-08):
#   http://nuc-cdp.digital.gov.ru/cdp/rootca_ssl_rsa2022.crt
#   http://nuc-cdp.digital.gov.ru/cdp/subca_ssl_rsa2024.crt
#
# Re-fetch if T‑Invest rotates the national PKI chain.
