#!/usr/bin/env python3
import abc
import argparse
import dataclasses
import enum
import json
import logging
import pathlib
import subprocess
import sys
import os
import time
from typing import Dict, List, Optional

import yaml


def with_progress(iterable):
    try:
        import progressbar

        return progressbar.progressbar(iterable, prefix="Experiments")
    except ImportError:
        return iterable


class Validation(enum.Enum):
    Correct = "correct"
    Incorrect = "incorrect"
    Unknown = "unknown"
    Error = "error"


@dataclasses.dataclass
class SolverOutput(object):
    validation: Validation
    message: str


@dataclasses.dataclass
class ProcessResult(abc.ABC):
    output: str
    error: str
    time: float

    @abc.abstractmethod
    def message(self) -> str:
        pass


@dataclasses.dataclass
class Timeout(ProcessResult):
    def message(self) -> str:
        return "Timeout"

    def __str__(self):
        return f"Timeout"


@dataclasses.dataclass
class Error(ProcessResult):
    code: int
    error_type: str

    def message(self) -> str:
        return self.error_type

    def __str__(self):
        return f"Error({self.error_type},{self.code})"


@dataclasses.dataclass
class Success(ProcessResult):
    solver_output: SolverOutput

    def message(self) -> str:
        return self.solver_output.message

    def __str__(self):
        return f"Success({self.time:.2f})"


def get_error(stderr):
    if "java.lang.StackOverflowError" in stderr:
        return "stackoverflow"
    if "java.lang.OutOfMemoryError" in stderr:
        return "memory"
    if "Can not create DoubleVector" in stderr:
        return "bounds"
    if "Iterative method did not converge" in stderr:
        return "convergence"
    if "ArrayIndexOutOfBounds" in stderr:
        return "memory"
    if "NegativeArraySizeException" in stderr:
        return "memory"
    if "IllegalArgumentException" in stderr:
        return "internal"
    logging.warning("Unrecognized error:\n%s", stderr)
    return "generic"


def parse_expected(val):
    v = str(val).lower()
    if v == "true":
        return True
    if v == "false":
        return False
    try:
        return float(val)
    except TypeError:
        return val


def compare(state, value, expected, precision=1e-6, relative=False):
    if value is None:
        return SolverOutput(Validation.Error, f"{state}: No value present")
    if type(value) != type(expected):
        return SolverOutput(
            Validation.Incorrect,
            f"{state}: Expected type {type(expected)} but got {type(value)}",
        )
    if isinstance(expected, float):
        if relative:
            expected_lower = (1 - precision) * expected
            expected_upper = (1 + precision) * expected
        else:
            expected_lower = expected - precision / 2
            expected_upper = expected + precision / 2
        if expected_lower <= value <= expected_upper:
            return SolverOutput(
                Validation.Correct,
                f"{state}: Value {value} within bounds {expected_lower}, {expected_upper}",
            )
        return SolverOutput(
            Validation.Incorrect,
            f"{state}: Value {value} outside bounds {expected_lower}, {expected_upper}",
        )
    if expected == value:
        return SolverOutput(
            Validation.Correct, f"{state}: Got expected value {expected}"
        )
    return SolverOutput(
        Validation.Incorrect, f"{state}: Expected {expected} but got {value} in {state}"
    )


@dataclasses.dataclass(frozen=True)
class Model(object):
    model_file: pathlib.Path
    constants: str
    properties_file: Optional[pathlib.Path]

    def name(self):
        return self.model_file.name

    def __str__(self):
        return f"{self.model_file.name}({self.constants})"


class QueryType(enum.Enum):
    Core = "core"
    Reachability = "reach"
    MeanPayoff = "mean-payoff"


@dataclasses.dataclass(frozen=True)
class Query(abc.ABC):
    @property
    @abc.abstractmethod
    def query_type(self) -> QueryType:
        pass

    @property
    @abc.abstractmethod
    def name(self) -> str:
        pass

    @staticmethod
    def parse(query_type, spec):
        if query_type == "core":
            return Core.do_parse(spec)
        if query_type == "reach":
            return Reachability.do_parse(spec)
        if query_type == "mean_payoff":
            return MeanPayoff.do_parse(spec)
        raise ValueError(f"Unknown query type {query_type}")

    def __str__(self):
        return self.name()


@dataclasses.dataclass(frozen=True)
class Core(Query):
    @staticmethod
    def do_parse(data):
        return Core()

    @property
    def query_type(self):
        return QueryType.Core

    @property
    def name(self):
        return f"core"


@dataclasses.dataclass(frozen=True)
class ValueQuery(Query, abc.ABC):
    expected_value: Optional


@dataclasses.dataclass(frozen=True)
class Reachability(ValueQuery):
    property: str

    @staticmethod
    def do_parse(data):
        expected_value = (
            parse_expected(data["expected"]) if "expected" in data else None
        )
        return Reachability(
            expected_value=expected_value,
            property=data["property"],
        )

    @property
    def query_type(self):
        return QueryType.Reachability

    @property
    def name(self):
        return f"reach({self.property})"


@dataclasses.dataclass(frozen=True)
class MeanPayoff(ValueQuery):
    rewards: str
    lower_bound: float
    upper_bound: float

    @staticmethod
    def do_parse(data):
        expected_value = (
            parse_expected(data["expected"]) if "expected" in data else None
        )
        return MeanPayoff(
            rewards=data["rewards"],
            lower_bound=data["reward_min"],
            upper_bound=data["reward_max"],
            expected_value=expected_value,
        )

    @property
    def query_type(self):
        return QueryType.MeanPayoff

    @property
    def name(self):
        return f"mean_payoff({self.rewards})"


class Solver(abc.ABC):
    @property
    @abc.abstractmethod
    def name(self) -> str:
        pass

    @abc.abstractmethod
    def is_supported(self, model: Model, query: Query) -> bool:
        pass

    @abc.abstractmethod
    def create_invocation(
        self, model: Model, query: Query, validate: bool
    ) -> subprocess.Popen:
        pass

    @abc.abstractmethod
    def check_output(self, stdout, stderr, model: Model, query: Query) -> SolverOutput:
        pass


@dataclasses.dataclass(frozen=True)
class PetSolver(Solver):
    path: pathlib.Path
    heuristic: str
    precision: float
    relative_error: bool
    solver_name: str

    @property
    def name(self) -> str:
        return self.solver_name

    def is_supported(self, model: Model, query: Query) -> bool:
        return True

    def model_arguments(self, model: Model) -> List[str]:
        args = ["--model", model.model_file]
        if model.constants:
            args += ["--const", model.constants]
        if model.properties_file:
            args += ["--properties", model.properties_file]
        return args

    def create_invocation(
        self, model: Model, query: Query, validate: bool
    ) -> subprocess.Popen:
        model_arguments = self.model_arguments(model)

        execution = [self.path]
        if isinstance(query, Core):
            execution += (
                ["core"]
                + model_arguments
                + ["--unbounded", "--precision", str(self.precision)]
                + (["--validate"] if validate else [])
            )
        elif isinstance(query, Reachability):
            execution += (
                ["reachability"]
                + model_arguments
                + ["--property", query.property, "--precision", str(self.precision)]
                + (["--relative"] if self.relative_error else [])
            )
        elif isinstance(query, MeanPayoff):
            execution += (
                ["mean-payoff"]
                + model_arguments
                + [
                    "--rewards",
                    str(query.rewards),
                    "--reward-min",
                    str(query.lower_bound),
                    "--reward-max",
                    str(query.upper_bound),
                ]
                + ["--precision", str(self.precision)]
                + (["--relative"] if self.relative_error else [])
            )
        else:
            raise AssertionError()

        os_env = os.environ.copy()
        env = {"PATH": os_env["PATH"], "SEED": os_env.get("SEED", "1234")}
        if "JAVA_HOME" in os_env:
            env["JAVA_HOME"] = os_env["JAVA_HOME"]
        if "JAVA_OPTS" in os_env:
            env["JAVA_OPTS"] = os_env["JAVA_OPTS"]

        return subprocess.Popen(
            execution,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            universal_newlines=True,
            env=env,
        )

    def check_output(self, stdout, stderr, model: Model, query: Query) -> SolverOutput:
        try:
            output = json.loads(stdout)
        except json.JSONDecodeError:
            return SolverOutput(Validation.Error, "Failed to parse JSON")
        if isinstance(query, Core):
            return SolverOutput(
                Validation.Correct, f"{output['unbounded_statistics']['states']} states"
            )
        if isinstance(query, Reachability) or isinstance(query, MeanPayoff):
            values = output["values"]
            if len(values) == 1:
                state, value = next(iter(values.items()))
            else:
                return SolverOutput(
                    Validation.Error,
                    f"Expected single state, got {' '.join(values.keys())}",
                )
            if query.expected_value is None:
                return SolverOutput(Validation.Unknown, value)
            return compare(
                state,
                value,
                query.expected_value,
                precision=self.precision,
                relative=self.relative_error,
            )
        raise AssertionError()


@dataclasses.dataclass(frozen=True)
class Instance(object):
    model: Model
    query: Query
    solver: Solver
    tags: List[str]

    def sort_key(self):
        return (
            str(self.query.query_type),
            self.model.name(),
            self.query.name,
            self.solver.name,
        )

    def __str__(self):
        return f"{self.solver.name} / {self.model.name()} / {self.query.name}"


def evaluate(solver: Solver, model: Model, query: Query, args) -> ProcessResult:
    timestamp = time.time()

    process = solver.create_invocation(model, query, args.validate)
    try:
        stdout, stderr = process.communicate(timeout=args.timeout)
    except subprocess.TimeoutExpired as e:
        process.kill()
        return Timeout(e.stdout, e.stderr, time.time() - timestamp)

    runtime = time.time() - timestamp
    if process.returncode:
        return Error(stdout, stderr, runtime, process.returncode, get_error(stderr))
    return Success(
        output=stdout,
        error=stderr,
        time=runtime,
        solver_output=solver.check_output(stdout, stderr, model, query),
    )


def do(args):
    if not args.models.exists():
        sys.exit(f"No file found at {args.models}")
    with args.models.open(mode="rt") as f:
        models_data = yaml.load(f, yaml.SafeLoader)
    if not args.solvers.exists():
        sys.exit(f"No file found at {args.solvers}")
    with args.solvers.open(mode="rt") as f:
        solvers_data = yaml.load(f, yaml.SafeLoader)

    filters = []
    if args.query_type:
        query_set = set(args.query_type)
        filters.append(lambda i: i.query.query_type() in query_set)
    if args.exclude_tag:
        exclude_set = set(args.exclude_tag)
        filters.append(lambda i: all(tag not in exclude_set for tag in i.tags))
    if args.include_tag:
        include_set = set(args.include_tag)
        filters.append(lambda i: any(tag in include_set for tag in i.tags))
    if args.model_name:
        filters.append(
            lambda i: any(name in i.model.name() for name in args.model_name)
        )

    solvers = []
    for solver in solvers_data:
        name = solver["name"]
        if args.solver_name and name not in args.solver_name:
            continue
        if solver["type"] == "pet":
            conf = solver["conf"]
            solvers.append(
                PetSolver(
                    solver_name=name,
                    path=conf["path"],
                    heuristic=conf.get("heuristic", "WEIGHTED"),
                    precision=float(conf.get("precision", 1e-6)),
                    relative_error=conf.get("error", "absolute") == "relative",
                )
            )
        else:
            raise ValueError(solver["type"])
    if not solvers:
        sys.exit("No solvers found")

    instances = []
    base_path = args.models.parent
    for description in models_data:
        tags = description.get("tags", [])
        model = description["model"]
        model_relative_path = model["path"]
        model_path: pathlib.Path = (base_path / model_relative_path).resolve()
        if not model_path.exists():
            sys.exit(f"Model {model_relative_path} does not exist")
        if "properties" in model:
            properties_path = (base_path / model["properties"]).resolve()
            if not properties_path.exists():
                sys.exit(f"Query file {properties_path} does not exist")
        else:
            properties_path = None
        model = Model(
            model_file=model_path,
            constants=model.get("const", ""),
            properties_file=properties_path,
        )

        queries = description.get("queries", [])
        if not queries:
            logging.warning("Model %s has no valid queries", model_relative_path)
        for query_description in queries:
            query_tags = query_description.get("tags", [])
            spec = query_description.get("spec", dict())
            query = Query.parse(query_description["type"], spec)
            for solver in solvers:
                instance = Instance(
                    solver=solver,
                    model=model,
                    query=query,
                    tags=frozenset(tags + query_tags),
                )
                if all(f(instance) for f in filters):
                    instances.append(instance)

    results: Dict[Instance, ProcessResult] = {}
    for instance in with_progress(instances):
        results[instance] = evaluate(
            instance.solver, instance.model, instance.query, args
        )

    headers = ["model", "query", "solver", "time", "message"]
    data = [
        (
            instance.model.name(),
            instance.query.name,
            instance.solver.name,
            result.time,
            result.message(),
        )
        for instance, result in sorted(results.items(), key=lambda x: x[0].sort_key())
    ]
    print()
    print()
    try:
        import tabulate

        print(tabulate.tabulate(data, headers))
    except ModuleNotFoundError:
        for d in data:
            print(d)
    if args.validate and not all(
        isinstance(result, Success)
        and result.solver_output.validation == Validation.Correct
        for result in results.values()
    ):
        sys.exit("Validation failed")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--models", type=pathlib.Path, help="Path to the models file")
    parser.add_argument("--solvers", type=pathlib.Path, help="Path to the solvers file")
    parser.add_argument("--timeout", type=float, help="Timeout")
    parser.add_argument(
        "--precision", type=float, help="Precision requirement", default=1e-6
    )
    parser.add_argument("--relative", action="store_true", help="Ensure relative error")

    parser.add_argument(
        "--exclude-tag",
        type=str,
        action="append",
        help="Model and query tags to exclude",
    )
    parser.add_argument(
        "--include-tag",
        type=str,
        action="append",
        help="Model and query tags to include",
    )
    parser.add_argument(
        "--query-type",
        type=QueryType,
        action="append",
        help="Type of query to test",
    )
    parser.add_argument(
        "--model-name", type=str, action="append", help="Model names to test"
    )
    parser.add_argument(
        "--solver-name", type=str, action="append", help="Solvers to invoke"
    )

    parser.add_argument(
        "--validate",
        action="store_true",
        help="Validate results (only consider those with expected value)",
    )
    do(parser.parse_args())
