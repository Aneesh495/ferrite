from setuptools import setup, find_packages

setup(
    name="ferrite-client",
    version="1.0.0",
    description="Python client SDK for Ferrite Time-Series and Telemetry Engine",
    author="Ferrite Contributors",
    packages=find_packages(),
    python_requires=">=3.8",
    classifiers=[
        "Programming Language :: Python :: 3",
        "Topic :: System :: Distributed Computing",
        "Topic :: Database",
    ],
)
