/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

/// Smithy auth scheme implementations.
pub mod auth;

/// Built-in Smithy connectors.
///
/// See the [module docs in `aws-smithy-runtime-api`](aws_smithy_runtime_api::client::connectors)
/// for more information about connectors.
pub mod connectors;

/// Utility to simplify config building for config and config overrides.
pub mod config_override;

/// The client orchestrator implementation
pub mod orchestrator;

/// Smithy code related to retry handling and token buckets.
///
/// This code defines when and how failed requests should be retried. It also defines the behavior
/// used to limit the rate at which requests are sent.
pub mod retries;

/// Utilities for testing orchestrators. An orchestrator missing required components will panic when
/// run. This module contains stub components that can be used when you only care about testing some
/// specific aspect of the orchestrator.
#[cfg(feature = "test-util")]
pub mod test_util;

mod timeout;

/// Smithy identity used by auth and signing.
pub mod identity;

/// Interceptors for Smithy clients.
pub mod interceptors;
