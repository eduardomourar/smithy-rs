/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

// TODO(enableNewSmithyRuntimeCleanup): Deprecate this crate and replace it with empty contents. Remove references to it in the code generator.

#![allow(clippy::derive_partial_eq_without_eq)]

//! AWS Signature Authentication Package
//!
//! This crate may be used to generate presigned URLs for unmodeled behavior such as `rds-iam-token`
//! or to sign requests to APIGateway-based services with IAM authorization.
//!
//! # Examples
//!
//! ## Generate RDS IAM Token
//! ```rust
//! use aws_credential_types::Credentials;
//! use aws_smithy_http::body::SdkBody;
//! use aws_types::SigningName;
//! use aws_types::region::{Region, SigningRegion};
//! use std::time::{Duration, SystemTime, UNIX_EPOCH};
//! use aws_sig_auth::signer::{self, SigningError, OperationSigningConfig, HttpSignatureType, RequestConfig};
//! use aws_smithy_runtime_api::client::identity::Identity;
//!
//! fn generate_rds_iam_token(
//!     db_hostname: &str,
//!     region: Region,
//!     port: u16,
//!     db_username: &str,
//!     identity: &Identity,
//!     timestamp: SystemTime,
//! ) -> Result<String, SigningError> {
//!     let signer = signer::SigV4Signer::new();
//!     let mut operation_config = OperationSigningConfig::default_config();
//!     operation_config.signature_type = HttpSignatureType::HttpRequestQueryParams;
//!     operation_config.expires_in = Some(Duration::from_secs(15 * 60));
//!     let request_config = RequestConfig {
//!         request_ts: timestamp,
//!         region: &SigningRegion::from(region),
//!         name: &SigningName::from_static("rds-db"),
//!         payload_override: None,
//!     };
//!     let mut request = http::Request::builder()
//!         .uri(format!(
//!             "http://{db_hostname}:{port}/?Action=connect&DBUser={db_user}",
//!             db_hostname = db_hostname,
//!             port = port,
//!             db_user = db_username
//!         ))
//!         .body(SdkBody::empty())
//!         .expect("valid request");
//!     let _signature = signer.sign(
//!         &operation_config,
//!         &request_config,
//!         identity,
//!         &mut request,
//!     )?;
//!     let mut uri = request.uri().to_string();
//!     assert!(uri.starts_with("http://"));
//!     let uri = uri.split_off("http://".len());
//!     Ok(uri)
//! }
//!
//! // You will need to get an `identity` from a credentials provider ahead of time
//! # let identity = Credentials::new("AKIDEXAMPLE", "secret", None, None, "example").into();
//! let token = generate_rds_iam_token(
//!     "prod-instance.us-east-1.rds.amazonaws.com",
//!     Region::from_static("us-east-1"),
//!     3306,
//!     "dbuser",
//!     &identity,
//!     // this value is hard coded to create deterministic signature for tests. Generally,
//!     // `SystemTime::now()` should be used
//!     UNIX_EPOCH + Duration::from_secs(1635257380)
//! ).expect("failed to generate token");
//! # // validate against token generated by the aws CLI
//! # assert_eq!(token, "prod-instance.us-east-1.rds.amazonaws.com:3306/?Action=connect&DBUser=dbuser&X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=AKIDEXAMPLE%2F20211026%2Fus-east-1%2Frds-db%2Faws4_request&X-Amz-Date=20211026T140940Z&X-Amz-Expires=900&X-Amz-SignedHeaders=host&X-Amz-Signature=9632f5f4fcd2087a3c523f55f72d2fe97fad03b71a0a23b8c1edfb104e8072d1");
//! ```
//!
//! ## Sign a request for APIGateway execute-api
//!
//! ```no_run
//! use aws_credential_types::provider::ProvideCredentials;
//! use aws_sig_auth::signer::{OperationSigningConfig, RequestConfig, SigV4Signer};
//! use aws_smithy_http::body::SdkBody;
//! use aws_types::region::{Region, SigningRegion};
//! use aws_types::SigningName;
//! use std::error::Error;
//! use std::time::SystemTime;
//! use aws_smithy_runtime_api::client::identity::Identity;
//! async fn sign_request(
//!     mut request: &mut http::Request<SdkBody>,
//!     region: Region,
//!     credentials_provider: &impl ProvideCredentials,
//! ) -> Result<(), Box<dyn Error + Send + Sync>> {
//!     let now = SystemTime::now();
//!     let signer = SigV4Signer::new();
//!     let request_config = RequestConfig {
//!         request_ts: now,
//!         region: &SigningRegion::from(region),
//!         name: &SigningName::from_static("execute-api"),
//!         payload_override: None,
//!     };
//!     let identity = credentials_provider.provide_credentials().await?.into();
//!     signer.sign(
//!         &OperationSigningConfig::default_config(),
//!         &request_config,
//!         &identity,
//!         &mut request,
//!     )?;
//!     Ok((()))
//! }
//! ```

#[cfg(feature = "sign-eventstream")]
pub mod event_stream;

pub mod middleware;
pub mod signer;
