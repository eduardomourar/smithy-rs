/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

use aws_credential_types::provider::SharedCredentialsProvider;
use aws_credential_types::Credentials;
use aws_sdk_s3::Client;
use aws_smithy_client::test_connection::infallible_connection_fn;
use aws_smithy_http::body::SdkBody;
use aws_smithy_types::error::metadata::ProvideErrorMetadata;
use aws_types::region::Region;
use aws_types::SdkConfig;

const ERROR_RESPONSE: &str = r#"<?xml version="1.0" encoding="UTF-8"?>
        <Error>
            <Code>SlowDown</Code>
            <Message>Please reduce your request rate.</Message>
            <RequestId>K2H6N7ZGQT6WHCEG</RequestId>
            <HostId>WWoZlnK4pTjKCYn6eNV7GgOurabfqLkjbSyqTvDMGBaI9uwzyNhSaDhOCPs8paFGye7S6b/AB3A=</HostId>
        </Error>
"#;

#[tokio::test]
async fn status_200_errors() {
    let conn = infallible_connection_fn(|_req| http::Response::new(SdkBody::from(ERROR_RESPONSE)));
    let sdk_config = SdkConfig::builder()
        .credentials_provider(SharedCredentialsProvider::new(Credentials::for_tests()))
        .region(Region::new("us-west-4"))
        .http_connector(conn)
        .build();
    let client = Client::new(&sdk_config);
    let error = client
        .delete_objects()
        .bucket("bucket")
        .send()
        .await
        .expect_err("should fail");
    assert_eq!(error.into_service_error().code(), Some("SlowDown"));
}
