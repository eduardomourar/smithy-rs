/*
 *  Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *  SPDX-License-Identifier: Apache-2.0
 */

//! Partition function to determine a partition for a given region
//!
//! This function supports adding regions dynamically, parsing a JSON file, and builder construction.
//!
//! If, at a future point, this interface stabilizes it is a good candidate for extraction into a
//! shared crate.
use crate::endpoint_lib::diagnostic::DiagnosticCollector;
use crate::endpoint_lib::partition::deser::deserialize_partitions;
use aws_smithy_json::deserialize::error::DeserializeError;
use regex_lite::Regex;
use std::borrow::Cow;
use std::collections::HashMap;

/// Determine the AWS partition metadata for a given region
#[derive(Clone, Debug, Default)]
pub(crate) struct PartitionResolver {
    partitions: Vec<PartitionMetadata>,
}

impl PartitionResolver {
    pub(crate) fn from_partitions(partitions: Vec<PartitionMetadata>) -> Self {
        Self { partitions }
    }
}

/// Partition result returned from partition resolver
pub(crate) struct Partition<'a> {
    name: &'a str,
    dns_suffix: &'a str,
    dual_stack_dns_suffix: &'a str,
    supports_fips: bool,
    supports_dual_stack: bool,
    implicit_global_region: &'a str,
}

#[allow(unused)]
impl Partition<'_> {
    pub(crate) fn name(&self) -> &str {
        self.name
    }

    pub(crate) fn dns_suffix(&self) -> &str {
        self.dns_suffix
    }

    pub(crate) fn supports_fips(&self) -> bool {
        self.supports_fips
    }

    pub(crate) fn dual_stack_dns_suffix(&self) -> &str {
        self.dual_stack_dns_suffix
    }

    pub(crate) fn supports_dual_stack(&self) -> bool {
        self.supports_dual_stack
    }

    pub(crate) fn implicit_global_region(&self) -> &str {
        self.implicit_global_region
    }
}

static DEFAULT_OVERRIDE: &PartitionOutputOverride = &PartitionOutputOverride {
    name: None,
    dns_suffix: None,
    dual_stack_dns_suffix: None,
    supports_fips: None,
    supports_dual_stack: None,
    implicit_global_region: None,
};

/// Merge the base output and the override output, dealing with `Cow`s
macro_rules! merge {
    ($base: expr, $output: expr, $field: ident) => {
        $output
            .$field
            .as_ref()
            .map(|s| s.as_ref())
            .unwrap_or($base.outputs.$field.as_ref())
    };
}

impl PartitionResolver {
    #[allow(unused)]
    pub(crate) fn empty() -> PartitionResolver {
        PartitionResolver { partitions: vec![] }
    }

    #[allow(unused)]
    pub(crate) fn add_partition(&mut self, partition: PartitionMetadata) {
        self.partitions.push(partition);
    }

    pub(crate) fn new_from_json(
        partition_dot_json: &[u8],
    ) -> Result<PartitionResolver, DeserializeError> {
        deserialize_partitions(partition_dot_json)
    }

    /// Resolve a partition for a given region
    ///
    /// 1. Enumerate each partition in the `partitions` array, and determine if the identifier to be
    ///    resolved matches an explicit region listed in the `regions` array for a given partition.
    ///    If identifier matches, proceed to step 4, otherwise continue to step 2.
    /// 2. Enumerate each partition in the `partitions` array, use the regular expression
    ///    `regionRegex` to determine if the identifier matches the regular expression. If the
    ///    identifier matches, proceed to step 4, otherwise continue to step 3.
    /// 3. If no partition is matched after exhausting step 1 and step 2, then fallback to matching
    ///    the identifier to the partition where `id == "aws"`, and proceed to step 4. If no `aws`
    ///    partition is present, return `None`.
    /// 4. After matching the identifier to a partition using one of the previous steps, the partition function should return a
    ///    typed data structure containing the fields in `outputs` in the matched partition. **Important:** If a specific region
    ///    was matched, the properties associated with that region **MUST** be merged with the `outputs` field.
    pub(crate) fn resolve_partition(
        &self,
        region: &str,
        e: &mut DiagnosticCollector,
    ) -> Option<Partition> {
        let mut explicit_match_partition = self
            .partitions
            .iter()
            .flat_map(|part| part.explicit_match(region));
        let mut regex_match_partition = self
            .partitions
            .iter()
            .flat_map(|part| part.regex_match(region));

        let (base, region_override) = explicit_match_partition
            .next()
            .or_else(|| regex_match_partition.next())
            .or_else(|| match self.partitions.iter().find(|p| p.id == "aws") {
                Some(partition) => Some((partition, None)),
                None => {
                    e.report_error("no AWS partition!");
                    None
                }
            })?;
        let region_override = region_override.as_ref().unwrap_or(&DEFAULT_OVERRIDE);
        Some(Partition {
            name: merge!(base, region_override, name),
            dns_suffix: merge!(base, region_override, dns_suffix),
            dual_stack_dns_suffix: merge!(base, region_override, dual_stack_dns_suffix),
            supports_fips: region_override
                .supports_fips
                .unwrap_or(base.outputs.supports_fips),
            supports_dual_stack: region_override
                .supports_dual_stack
                .unwrap_or(base.outputs.supports_dual_stack),
            implicit_global_region: merge!(base, region_override, implicit_global_region),
        })
    }
}

type Str = Cow<'static, str>;

#[derive(Clone, Debug)]
pub(crate) struct PartitionMetadata {
    id: Str,
    region_regex: Regex,
    regions: HashMap<Str, PartitionOutputOverride>,
    outputs: PartitionOutput,
}

#[derive(Default)]
pub(crate) struct PartitionMetadataBuilder {
    pub(crate) id: Option<Str>,
    pub(crate) region_regex: Option<Regex>,
    pub(crate) regions: HashMap<Str, PartitionOutputOverride>,
    pub(crate) outputs: Option<PartitionOutputOverride>,
}

impl PartitionMetadataBuilder {
    pub(crate) fn build(self) -> PartitionMetadata {
        PartitionMetadata {
            id: self.id.expect("id must be defined"),
            region_regex: self.region_regex.expect("region regex must be defined"),
            regions: self.regions,
            outputs: self
                .outputs
                .expect("outputs must be defined")
                .into_partition_output()
                .expect("missing fields on outputs"),
        }
    }
}

impl PartitionMetadata {
    fn explicit_match(
        &self,
        region: &str,
    ) -> Option<(&PartitionMetadata, Option<&PartitionOutputOverride>)> {
        self.regions
            .get(region)
            .map(|output_override| (self, Some(output_override)))
    }

    fn regex_match(
        &self,
        region: &str,
    ) -> Option<(&PartitionMetadata, Option<&PartitionOutputOverride>)> {
        if self.region_regex.is_match(region) {
            Some((self, None))
        } else {
            None
        }
    }
}

#[derive(Clone, Debug)]
pub(crate) struct PartitionOutput {
    name: Str,
    dns_suffix: Str,
    dual_stack_dns_suffix: Str,
    supports_fips: bool,
    supports_dual_stack: bool,
    implicit_global_region: Str,
}

#[derive(Clone, Debug, Default)]
pub(crate) struct PartitionOutputOverride {
    name: Option<Str>,
    dns_suffix: Option<Str>,
    dual_stack_dns_suffix: Option<Str>,
    supports_fips: Option<bool>,
    supports_dual_stack: Option<bool>,
    implicit_global_region: Option<Str>,
}

impl PartitionOutputOverride {
    pub(crate) fn into_partition_output(
        self,
    ) -> Result<PartitionOutput, Box<dyn std::error::Error>> {
        Ok(PartitionOutput {
            name: self.name.ok_or("missing name")?,
            dns_suffix: self.dns_suffix.ok_or("missing dnsSuffix")?,
            dual_stack_dns_suffix: self
                .dual_stack_dns_suffix
                .ok_or("missing dual_stackDnsSuffix")?,
            supports_fips: self.supports_fips.ok_or("missing supports fips")?,
            supports_dual_stack: self
                .supports_dual_stack
                .ok_or("missing supportsDualstack")?,
            implicit_global_region: self
                .implicit_global_region
                .ok_or("missing implicitGlobalRegion")?,
        })
    }
}

/// JSON deserializers for partition metadata
///
/// This code was generated by smithy-rs and then hand edited for clarity
mod deser {
    use crate::endpoint_lib::partition::{
        PartitionMetadata, PartitionMetadataBuilder, PartitionOutputOverride, PartitionResolver,
    };
    use aws_smithy_json::deserialize::token::{
        expect_bool_or_null, expect_start_object, expect_string_or_null, skip_value,
    };
    use aws_smithy_json::deserialize::{error::DeserializeError, json_token_iter, Token};
    use regex_lite::Regex;
    use std::borrow::Cow;
    use std::collections::HashMap;

    pub(crate) fn deserialize_partitions(
        value: &[u8],
    ) -> Result<PartitionResolver, DeserializeError> {
        let mut tokens_owned = json_token_iter(value).peekable();
        let tokens = &mut tokens_owned;
        expect_start_object(tokens.next())?;
        let mut resolver = None;
        loop {
            match tokens.next().transpose()? {
                Some(Token::EndObject { .. }) => break,
                Some(Token::ObjectKey { key, .. }) => match key.to_unescaped()?.as_ref() {
                    "partitions" => {
                        resolver = Some(PartitionResolver::from_partitions(deser_partitions(
                            tokens,
                        )?));
                    }
                    _ => skip_value(tokens)?,
                },
                other => {
                    return Err(DeserializeError::custom(format!(
                        "expected object key or end object, found: {:?}",
                        other
                    )))
                }
            }
        }
        if tokens.next().is_some() {
            return Err(DeserializeError::custom(
                "found more JSON tokens after completing parsing",
            ));
        }
        resolver.ok_or_else(|| DeserializeError::custom("did not find partitions array"))
    }

    fn deser_partitions<'a, I>(
        tokens: &mut std::iter::Peekable<I>,
    ) -> Result<Vec<PartitionMetadata>, DeserializeError>
    where
        I: Iterator<Item = Result<Token<'a>, DeserializeError>>,
    {
        match tokens.next().transpose()? {
            Some(Token::StartArray { .. }) => {
                let mut items = Vec::new();
                loop {
                    match tokens.peek() {
                        Some(Ok(Token::EndArray { .. })) => {
                            tokens.next().transpose().unwrap();
                            break;
                        }
                        _ => {
                            items.push(deser_partition(tokens)?);
                        }
                    }
                }
                Ok(items)
            }
            _ => Err(DeserializeError::custom("expected start array")),
        }
    }

    pub(crate) fn deser_partition<'a, I>(
        tokens: &mut std::iter::Peekable<I>,
    ) -> Result<PartitionMetadata, DeserializeError>
    where
        I: Iterator<Item = Result<Token<'a>, DeserializeError>>,
    {
        match tokens.next().transpose()? {
            Some(Token::StartObject { .. }) => {
                let mut builder = PartitionMetadataBuilder::default();
                loop {
                    match tokens.next().transpose()? {
                        Some(Token::EndObject { .. }) => break,
                        Some(Token::ObjectKey { key, .. }) => match key.to_unescaped()?.as_ref() {
                            "id" => {
                                builder.id = token_to_str(tokens.next())?;
                            }
                            "regionRegex" => {
                                builder.region_regex = token_to_str(tokens.next())?
                                    .map(|region_regex| Regex::new(&region_regex))
                                    .transpose()
                                    .map_err(|_e| DeserializeError::custom("invalid regex"))?;
                            }
                            "regions" => {
                                builder.regions = deser_explicit_regions(tokens)?;
                            }
                            "outputs" => {
                                builder.outputs = deser_outputs(tokens)?;
                            }
                            _ => skip_value(tokens)?,
                        },
                        other => {
                            return Err(DeserializeError::custom(format!(
                                "expected object key or end object, found: {:?}",
                                other
                            )))
                        }
                    }
                }
                Ok(builder.build())
            }
            _ => Err(DeserializeError::custom("expected start object")),
        }
    }

    #[allow(clippy::type_complexity, non_snake_case)]
    pub(crate) fn deser_explicit_regions<'a, I>(
        tokens: &mut std::iter::Peekable<I>,
    ) -> Result<HashMap<super::Str, PartitionOutputOverride>, DeserializeError>
    where
        I: Iterator<Item = Result<Token<'a>, DeserializeError>>,
    {
        match tokens.next().transpose()? {
            Some(Token::StartObject { .. }) => {
                let mut map = HashMap::new();
                loop {
                    match tokens.next().transpose()? {
                        Some(Token::EndObject { .. }) => break,
                        Some(Token::ObjectKey { key, .. }) => {
                            let key = key.to_unescaped().map(|u| u.into_owned())?;
                            let value = deser_outputs(tokens)?;
                            if let Some(value) = value {
                                map.insert(key.into(), value);
                            }
                        }
                        other => {
                            return Err(DeserializeError::custom(format!(
                                "expected object key or end object, found: {:?}",
                                other
                            )))
                        }
                    }
                }
                Ok(map)
            }
            _ => Err(DeserializeError::custom("expected start object")),
        }
    }

    /// Convert a token to `Str` (a potentially static String)
    fn token_to_str(
        token: Option<Result<Token, DeserializeError>>,
    ) -> Result<Option<super::Str>, DeserializeError> {
        Ok(expect_string_or_null(token)?
            .map(|s| s.to_unescaped().map(|u| u.into_owned()))
            .transpose()?
            .map(Cow::Owned))
    }

    fn deser_outputs<'a, I>(
        tokens: &mut std::iter::Peekable<I>,
    ) -> Result<Option<PartitionOutputOverride>, DeserializeError>
    where
        I: Iterator<Item = Result<Token<'a>, DeserializeError>>,
    {
        match tokens.next().transpose()? {
            Some(Token::StartObject { .. }) => {
                #[allow(unused_mut)]
                let mut builder = PartitionOutputOverride::default();
                loop {
                    match tokens.next().transpose()? {
                        Some(Token::EndObject { .. }) => break,
                        Some(Token::ObjectKey { key, .. }) => match key.to_unescaped()?.as_ref() {
                            "name" => {
                                builder.name = token_to_str(tokens.next())?;
                            }
                            "dnsSuffix" => {
                                builder.dns_suffix = token_to_str(tokens.next())?;
                            }
                            "dualStackDnsSuffix" => {
                                builder.dual_stack_dns_suffix = token_to_str(tokens.next())?;
                            }
                            "supportsFIPS" => {
                                builder.supports_fips = expect_bool_or_null(tokens.next())?;
                            }
                            "supportsDualStack" => {
                                builder.supports_dual_stack = expect_bool_or_null(tokens.next())?;
                            }
                            "implicitGlobalRegion" => {
                                builder.implicit_global_region = token_to_str(tokens.next())?;
                            }
                            _ => skip_value(tokens)?,
                        },
                        other => {
                            return Err(DeserializeError::custom(format!(
                                "expected object key or end object, found: {:?}",
                                other
                            )))
                        }
                    }
                }
                Ok(Some(builder))
            }
            _ => Err(DeserializeError::custom("expected start object")),
        }
    }
}

#[cfg(test)]
mod test {
    use crate::endpoint_lib::diagnostic::DiagnosticCollector;
    use crate::endpoint_lib::partition::{
        Partition, PartitionMetadata, PartitionOutput, PartitionOutputOverride, PartitionResolver,
    };
    use regex_lite::Regex;
    use std::collections::HashMap;

    fn resolve<'a>(resolver: &'a PartitionResolver, region: &str) -> Partition<'a> {
        resolver
            .resolve_partition(region, &mut DiagnosticCollector::new())
            .expect("could not resolve partition")
    }

    #[test]
    fn deserialize_partitions() {
        let partitions = r#"{
  "version": "1.1",
  "partitions": [
    {
      "id": "aws",
      "regionRegex": "^(us|eu|ap|sa|ca|me|af)-\\w+-\\d+$",
      "regions": {
        "af-south-1": {},
        "af-east-1": {},
        "ap-northeast-1": {},
        "ap-northeast-2": {},
        "ap-northeast-3": {},
        "ap-south-1": {},
        "ap-southeast-1": {},
        "ap-southeast-2": {},
        "ap-southeast-3": {},
        "ca-central-1": {},
        "eu-central-1": {},
        "eu-north-1": {},
        "eu-south-1": {},
        "eu-west-1": {},
        "eu-west-2": {},
        "eu-west-3": {},
        "me-south-1": {},
        "sa-east-1": {},
        "us-east-1": {},
        "us-east-2": {},
        "us-west-1": {},
        "us-west-2": {},
        "aws-global": {}
      },
      "outputs": {
        "name": "aws",
        "dnsSuffix": "amazonaws.com",
        "dualStackDnsSuffix": "api.aws",
        "supportsFIPS": true,
        "supportsDualStack": true,
        "implicitGlobalRegion": "us-east-1"
      }
    },
    {
      "id": "aws-us-gov",
      "regionRegex": "^us\\-gov\\-\\w+\\-\\d+$",
      "regions": {
        "us-gov-west-1": {},
        "us-gov-east-1": {},
        "aws-us-gov-global": {}
      },
      "outputs": {
        "name": "aws-us-gov",
        "dnsSuffix": "amazonaws.com",
        "dualStackDnsSuffix": "api.aws",
        "supportsFIPS": true,
        "supportsDualStack": true,
        "implicitGlobalRegion": "us-gov-east-1"
      }
    },
    {
      "id": "aws-cn",
      "regionRegex": "^cn\\-\\w+\\-\\d+$",
      "regions": {
        "cn-north-1": {},
        "cn-northwest-1": {},
        "aws-cn-global": {}
      },
      "outputs": {
        "name": "aws-cn",
        "dnsSuffix": "amazonaws.com.cn",
        "dualStackDnsSuffix": "api.amazonwebservices.com.cn",
        "supportsFIPS": true,
        "supportsDualStack": true,
        "implicitGlobalRegion": "cn-north-1"
      }
    },
    {
      "id": "aws-iso",
      "regionRegex": "^us\\-iso\\-\\w+\\-\\d+$",
      "outputs": {
        "name": "aws-iso",
        "dnsSuffix": "c2s.ic.gov",
        "supportsFIPS": true,
        "supportsDualStack": false,
        "dualStackDnsSuffix": "c2s.ic.gov",
        "implicitGlobalRegion": "us-iso-foo-1"
      },
      "regions": {}
    },
    {
      "id": "aws-iso-b",
      "regionRegex": "^us\\-isob\\-\\w+\\-\\d+$",
      "outputs": {
        "name": "aws-iso-b",
        "dnsSuffix": "sc2s.sgov.gov",
        "supportsFIPS": true,
        "supportsDualStack": false,
        "dualStackDnsSuffix": "sc2s.sgov.gov",
        "implicitGlobalRegion": "us-isob-foo-1"
      },
      "regions": {}
    }
  ]
}"#;
        let resolver =
            super::deser::deserialize_partitions(partitions.as_bytes()).expect("valid resolver");
        assert_eq!(resolve(&resolver, "cn-north-1").name, "aws-cn");
        assert_eq!(
            resolve(&resolver, "cn-north-1").dns_suffix,
            "amazonaws.com.cn"
        );
        assert_eq!(resolver.partitions.len(), 5);
        assert_eq!(
            resolve(&resolver, "af-south-1").implicit_global_region,
            "us-east-1"
        );
    }

    #[test]
    fn resolve_partitions() {
        let mut resolver = PartitionResolver::empty();
        let new_suffix = PartitionOutputOverride {
            dns_suffix: Some("mars.aws".into()),
            ..Default::default()
        };
        resolver.add_partition(PartitionMetadata {
            id: "aws".into(),
            region_regex: Regex::new("^(us|eu|ap|sa|ca|me|af)-\\w+-\\d+$").unwrap(),
            regions: HashMap::from([("mars-east-2".into(), new_suffix)]),
            outputs: PartitionOutput {
                name: "aws".into(),
                dns_suffix: "amazonaws.com".into(),
                dual_stack_dns_suffix: "api.aws".into(),
                supports_fips: true,
                supports_dual_stack: true,
                implicit_global_region: "us-east-1".into(),
            },
        });
        resolver.add_partition(PartitionMetadata {
            id: "other".into(),
            region_regex: Regex::new("^(other)-\\w+-\\d+$").unwrap(),
            regions: Default::default(),
            outputs: PartitionOutput {
                name: "other".into(),
                dns_suffix: "other.amazonaws.com".into(),
                dual_stack_dns_suffix: "other.aws".into(),
                supports_fips: false,
                supports_dual_stack: true,
                implicit_global_region: "other-south-2".into(),
            },
        });
        assert_eq!(resolve(&resolver, "us-east-1").name, "aws");
        assert_eq!(resolve(&resolver, "other-west-2").name, "other");
        // mars-east-1 hits aws through the default fallback
        assert_eq!(
            resolve(&resolver, "mars-east-1").dns_suffix,
            "amazonaws.com"
        );
        // mars-east-2 hits aws through the region override
        assert_eq!(resolve(&resolver, "mars-east-2").dns_suffix, "mars.aws");
    }
}
