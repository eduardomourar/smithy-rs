/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

use aws_smithy_async::rt::sleep::{AsyncSleep, SharedAsyncSleep};
use aws_smithy_http::body::SdkBody;
use aws_smithy_http::result::ConnectorError;
use aws_smithy_protocol_test::{assert_ok, validate_body, MediaType};
use aws_smithy_runtime_api::client::connectors::{HttpConnector, HttpConnectorFuture};
use aws_smithy_runtime_api::client::orchestrator::{HttpRequest, HttpResponse};
use http::header::{HeaderName, CONTENT_TYPE};
use std::fmt::Debug;
use std::ops::Deref;
use std::sync::{Arc, Mutex};
use std::time::Duration;

type ConnectionEvents = Vec<ConnectionEvent>;

/// Test data for the [`EventConnector`].
///
/// Each `ConnectionEvent` represents one HTTP request and response
/// through the connector. Optionally, a latency value can be set to simulate
/// network latency (done via async sleep in the `EventConnector`).
#[derive(Debug)]
pub struct ConnectionEvent {
    latency: Duration,
    req: HttpRequest,
    res: HttpResponse,
}

impl ConnectionEvent {
    /// Creates a new `ConnectionEvent`.
    pub fn new(req: HttpRequest, res: HttpResponse) -> Self {
        Self {
            res,
            req,
            latency: Duration::from_secs(0),
        }
    }

    /// Add simulated latency to this `ConnectionEvent`
    pub fn with_latency(mut self, latency: Duration) -> Self {
        self.latency = latency;
        self
    }

    /// Returns the test request.
    pub fn request(&self) -> &HttpRequest {
        &self.req
    }

    /// Returns the test response.
    pub fn response(&self) -> &HttpResponse {
        &self.res
    }
}

impl From<(HttpRequest, HttpResponse)> for ConnectionEvent {
    fn from((req, res): (HttpRequest, HttpResponse)) -> Self {
        Self::new(req, res)
    }
}

#[derive(Debug)]
struct ValidateRequest {
    expected: HttpRequest,
    actual: HttpRequest,
}

impl ValidateRequest {
    fn assert_matches(&self, index: usize, ignore_headers: &[HeaderName]) {
        let (actual, expected) = (&self.actual, &self.expected);
        assert_eq!(
            actual.uri(),
            expected.uri(),
            "Request #{index} - URI doesn't match expected value"
        );
        for (name, value) in expected.headers() {
            if !ignore_headers.contains(name) {
                let actual_header = actual
                    .headers()
                    .get(name)
                    .unwrap_or_else(|| panic!("Request #{index} - Header {name:?} is missing"));
                assert_eq!(
                    actual_header.to_str().unwrap(),
                    value.to_str().unwrap(),
                    "Request #{index} - Header {name:?} doesn't match expected value",
                );
            }
        }
        let actual_str = std::str::from_utf8(actual.body().bytes().unwrap_or(&[]));
        let expected_str = std::str::from_utf8(expected.body().bytes().unwrap_or(&[]));
        let media_type = if actual
            .headers()
            .get(CONTENT_TYPE)
            .map(|v| v.to_str().unwrap().contains("json"))
            .unwrap_or(false)
        {
            MediaType::Json
        } else {
            MediaType::Other("unknown".to_string())
        };
        match (actual_str, expected_str) {
            (Ok(actual), Ok(expected)) => assert_ok(validate_body(actual, expected, media_type)),
            _ => assert_eq!(
                actual.body().bytes(),
                expected.body().bytes(),
                "Request #{index} - Body contents didn't match expected value"
            ),
        };
    }
}

/// Request/response event-driven connector for use in tests.
///
/// A basic test connection. It will:
/// - Respond to requests with a preloaded series of responses
/// - Record requests for future examination
#[derive(Debug, Clone)]
pub struct EventConnector {
    data: Arc<Mutex<ConnectionEvents>>,
    requests: Arc<Mutex<Vec<ValidateRequest>>>,
    sleep_impl: SharedAsyncSleep,
}

impl EventConnector {
    /// Creates a new event connector.
    pub fn new(mut data: ConnectionEvents, sleep_impl: impl Into<SharedAsyncSleep>) -> Self {
        data.reverse();
        EventConnector {
            data: Arc::new(Mutex::new(data)),
            requests: Default::default(),
            sleep_impl: sleep_impl.into(),
        }
    }

    fn requests(&self) -> impl Deref<Target = Vec<ValidateRequest>> + '_ {
        self.requests.lock().unwrap()
    }

    /// Asserts the expected requests match the actual requests.
    ///
    /// The expected requests are given as the connection events when the `EventConnector`
    /// is created. The `EventConnector` will record the actual requests and assert that
    /// they match the expected requests.
    ///
    /// A list of headers that should be ignored when comparing requests can be passed
    /// for cases where headers are non-deterministic or are irrelevant to the test.
    #[track_caller]
    pub fn assert_requests_match(&self, ignore_headers: &[HeaderName]) {
        for (i, req) in self.requests().iter().enumerate() {
            req.assert_matches(i, ignore_headers)
        }
        let remaining_requests = self.data.lock().unwrap();
        let number_of_remaining_requests = remaining_requests.len();
        let actual_requests = self.requests().len();
        assert!(
            remaining_requests.is_empty(),
            "Expected {number_of_remaining_requests} additional requests (only {actual_requests} sent)",
        );
    }
}

impl HttpConnector for EventConnector {
    fn call(&self, request: HttpRequest) -> HttpConnectorFuture {
        let (res, simulated_latency) = if let Some(event) = self.data.lock().unwrap().pop() {
            self.requests.lock().unwrap().push(ValidateRequest {
                expected: event.req,
                actual: request,
            });

            (Ok(event.res.map(SdkBody::from)), event.latency)
        } else {
            (
                Err(ConnectorError::other("No more data".into(), None)),
                Duration::from_secs(0),
            )
        };

        let sleep = self.sleep_impl.sleep(simulated_latency);
        HttpConnectorFuture::new(async move {
            sleep.await;
            res
        })
    }
}
